package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.io.FileDescriptor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see	nachos.vm.VMProcess
 * @see	nachos.network.NetProcess
 */
public class UserProcess {
    /**
     * Allocate a new process.
     */
    public UserProcess() {
	int numPhysPages = Machine.processor().getNumPhysPages();
	pageTable = new TranslationEntry[numPhysPages];
	childrenProcess = new HashMap<Integer, UserProcess>();
	processesExitStatus = new HashMap<Integer, Integer>();
	processesWithUnhandledException = new HashSet<Integer>();

	/* stdin */
	fileDiscriptors[0] = UserKernel.console.openForReading();
	/* stdout */
	fileDiscriptors[1] = UserKernel.console.openForWriting();

	processLock.acquire();
	pid = nextPid++;
	processLock.release();
    }

    /**
     * Allocate and return a new process of the correct class. The class name
     * is specified by the <tt>nachos.conf</tt> key
     * <tt>Kernel.processClassName</tt>.
     *
     * @return	a new process of the correct class.
     */
    public static UserProcess newUserProcess() {
	return (UserProcess)Lib.constructObject(Machine.getProcessClassName());
    }

    /**
     * Execute the specified program with the specified arguments. Attempts to
     * load the program, and then forks a thread to run it.
     *
     * @param	name	the name of the file containing the executable.
     * @param	args	the arguments to pass to the executable.
     * @return	<tt>true</tt> if the program was successfully executed.
     */
    public boolean execute(String name, String[] args) {
	if (!load(name, args))
	    return false;

	processLock.acquire();
	numOfRunningProcesses++;
	processLock.release();

	thread = (UThread) new UThread(this).setName(name);
	thread.fork();

	return true;
    }

    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
	Machine.processor().setPageTable(pageTable);
    }

    /**
     * Read a null-terminated string from this process's virtual memory. Read
     * at most <tt>maxLength + 1</tt> bytes from the specified address, search
     * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
     * without including the null terminator. If no null terminator is found,
     * returns <tt>null</tt>.
     *
     * @param	vaddr	the starting virtual address of the null-terminated
     *			string.
     * @param	maxLength	the maximum number of characters in the string,
     *				not including the null terminator.
     * @return	the string read, or <tt>null</tt> if no null terminator was
     *		found.
     */
    public String readVirtualMemoryString(int vaddr, int maxLength) {
	Lib.assertTrue(maxLength >= 0);

	byte[] bytes = new byte[maxLength+1];

	int bytesRead = readVirtualMemory(vaddr, bytes);

	for (int length=0; length<bytesRead; length++) {
	    if (bytes[length] == 0)
		return new String(bytes, 0, length);
	}

	return null;
    }

    /**
     * Transfer data from this process's virtual memory to all of the specified
     * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param	vaddr	the first byte of virtual memory to read.
     * @param	data	the array where the data will be stored.
     * @return	the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data) {
	return readVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from this process's virtual memory to the specified array.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param	vaddr	the first byte of virtual memory to read.
     * @param	data	the array where the data will be stored.
     * @param	offset	the first byte to write in the array.
     * @param	length	the number of bytes to transfer from virtual memory to
     *			the array.
     * @return	the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data, int offset,
				 int length) {
	Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

	byte[] memory = Machine.processor().getMemory();
	int vpn = Processor.pageFromAddress(vaddr);
	int ppc = Processor.offsetFromAddress(vaddr);
	int readBytes = 0;
	TranslationEntry entry = null;

	while (length > 0) {
	    if (vpn > numPages) return readBytes;
	    entry = pageTable[vpn];
	    if (!entry.valid) return readBytes;

	    entry.used = true;

	    int paddr = entry.ppn * pageSize + ppc;
	    int amount = Math.min(pageSize - ppc, length);
	    System.arraycopy(memory, paddr, data, offset, amount);
	    vpn++;
	    length -= amount;
	    offset += amount;
	    ppc = 0;
	    readBytes += amount;
	}

	return readBytes;
    }

    /**
     * Transfer all data from the specified array to this process's virtual
     * memory.
     * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param	vaddr	the first byte of virtual memory to write.
     * @param	data	the array containing the data to transfer.
     * @return	the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data) {
	return writeVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from the specified array to this process's virtual memory.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param	vaddr	the first byte of virtual memory to write.
     * @param	data	the array containing the data to transfer.
     * @param	offset	the first byte to transfer from the array.
     * @param	length	the number of bytes to transfer from the array to
     *			virtual memory.
     * @return	the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data, int offset,
				  int length) {
	Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

	byte[] memory = Machine.processor().getMemory();
	int vpn = Processor.pageFromAddress(vaddr);
	int ppc = Processor.offsetFromAddress(vaddr);
	int writeBytes = 0;
	TranslationEntry entry = null;

	while (length > 0) {
	    if (vpn > numPages)
		return writeBytes;

	    entry = pageTable[vpn];

	    if (!entry.valid || entry.readOnly)
		return writeBytes;

	    entry.used = true;
	    entry.dirty = true;

	    /* physical address */
	    int paddr = entry.ppn * pageSize + ppc;

	    /* copy a page at most at a time */
	    int amount = Math.min((pageSize - ppc), length);
	    System.arraycopy(data, offset, memory, paddr, amount);

	    vpn++;
	    length -= amount;
	    offset += amount;
	    ppc = 0;
	    writeBytes += amount;
	}

	return writeBytes;
    }

    /**
     * Load the executable with the specified name into this process, and
     * prepare to pass it the specified arguments. Opens the executable, reads
     * its header information, and copies sections and arguments into this
     * process's virtual memory.
     *
     * @param	name	the name of the file containing the executable.
     * @param	args	the arguments to pass to the executable.
     * @return	<tt>true</tt> if the executable was successfully loaded.
     */
    private boolean load(String name, String[] args) {
	Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");
	
	OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
	if (executable == null) {
	    Lib.debug(dbgProcess, "\topen failed");
	    return false;
	}

	try {
	    coff = new Coff(executable);
	}
	catch (EOFException e) {
	    executable.close();
	    Lib.debug(dbgProcess, "\tcoff load failed");
	    return false;
	}

	// make sure the sections are contiguous and start at page 0
	numPages = 0;
	for (int s=0; s<coff.getNumSections(); s++) {
	    CoffSection section = coff.getSection(s);
	    if (section.getFirstVPN() != numPages) {
		coff.close();
		Lib.debug(dbgProcess, "\tfragmented executable");
		return false;
	    }
	    numPages += section.getLength();
	}

	// make sure the argv array will fit in one page
	byte[][] argv = new byte[args.length][];
	int argsSize = 0;
	for (int i=0; i<args.length; i++) {
	    argv[i] = args[i].getBytes();
	    // 4 bytes for argv[] pointer; then string plus one for null byte
	    argsSize += 4 + argv[i].length + 1;
	}
	if (argsSize > pageSize) {
	    coff.close();
	    Lib.debug(dbgProcess, "\targuments too long");
	    return false;
	}

	// program counter initially points at the program entry point
	initialPC = coff.getEntryPoint();	

	// next comes the stack; stack pointer initially points to top of it
	numPages += stackPages;
	initialSP = numPages*pageSize;

	// and finally reserve 1 page for arguments
	numPages++;

	if (!loadSections())
	    return false;

	// store arguments in last page
	int entryOffset = (numPages-1)*pageSize;
	int stringOffset = entryOffset + args.length*4;

	this.argc = args.length;
	this.argv = entryOffset;
	
	for (int i=0; i<argv.length; i++) {
	    byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
	    Lib.assertTrue(writeVirtualMemory(entryOffset,stringOffsetBytes) == 4);
	    entryOffset += 4;
	    Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
		       argv[i].length);
	    stringOffset += argv[i].length;
	    Lib.assertTrue(writeVirtualMemory(stringOffset,new byte[] { 0 }) == 1);
	    stringOffset += 1;
	}

	return true;
    }

    /**
     * Allocates memory for this process, and loads the COFF sections into
     * memory. If this returns successfully, the process will definitely be
     * run (this is the last step in process initialization that can fail).
     *
     * @return	<tt>true</tt> if the sections were successfully loaded.
     */
    protected boolean loadSections() {
	if (numPages > Machine.processor().getNumPhysPages()) {
	    coff.close();
	    Lib.debug(dbgProcess, "\tinsufficient physical memory");
	    return false;
	}

	LinkedList<Integer> memoryPages = UserKernel.getFreePhysicalPages(numPages);
	if (memoryPages == null) {
	    coff.close();
	    return false;
	}

	pageTable = new TranslationEntry[numPages];
	for(int i = 0; i < numPages; i++) {
	    int ppn = memoryPages.pollFirst();
	    pageTable[i] = new TranslationEntry(i, ppn, true, false, false, false);
	}

	// load sections
	for (int s=0; s<coff.getNumSections(); s++) {
	    CoffSection section = coff.getSection(s);
	    
	    Lib.debug(dbgProcess, "\tinitializing " + section.getName()
		      + " section (" + section.getLength() + " pages)");

	    for (int i=0; i<section.getLength(); i++) {
		int vpn = section.getFirstVPN()+i;
		section.loadPage(i, pageTable[vpn].ppn);
		pageTable[vpn].readOnly = section.isReadOnly();
	    }
	}

	return true;
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
	LinkedList<Integer> ppnPages = new LinkedList<Integer>();
	for (int i = 0; i < numPages; i++) {
	    if (pageTable[i] != null && pageTable[i].valid) {
		ppnPages.add(pageTable[i].ppn);
		pageTable[i].valid = false;
		pageTable[i].ppn = -1;
	    }
	}
	UserKernel.releasePhysicalPages(ppnPages);
	coff.close();
    }

    /**
     * Initialize the processor's registers in preparation for running the
     * program loaded into this process. Set the PC register to point at the
     * start function, set the stack pointer register to point at the top of
     * the stack, set the A0 and A1 registers to argc and argv, respectively,
     * and initialize all other registers to 0.
     */
    public void initRegisters() {
	Processor processor = Machine.processor();

	// by default, everything's 0
	for (int i=0; i<processor.numUserRegisters; i++)
	    processor.writeRegister(i, 0);

	// initialize PC and SP according
	processor.writeRegister(Processor.regPC, initialPC);
	processor.writeRegister(Processor.regSP, initialSP);

	// initialize the first two argument registers to argc and argv
	processor.writeRegister(Processor.regA0, argc);
	processor.writeRegister(Processor.regA1, argv);
    }

    /**
     * Handle the halt() system call. 
     */
    private int handleHalt() {

	if (pid != 1)
	    return 0;

	Machine.halt();

	Lib.assertNotReached("Machine.halt() did not halt machine!");
	return 0;
    }

    private int handleOpen(int nameAddr, boolean create) {
	/** The maximum length of strings as arguments to system calls is 256 bytes */
	String name = readVirtualMemoryString(nameAddr, 256);
	if (null == name) return -1;

	/** find a unused file descriptor to refer to the file*/
	int fd;
	for (fd = 0; fd < fileDiscriptors.length; fd++) {
	    if (fileDiscriptors[fd] == null)
		break;
	}

	/** cannot support more than 16 files concurrently */
	if (fd == fileDiscriptors.length)
	    return -1;

	// Stub system handles reference count as stated on piazza
	OpenFile file = UserKernel.fileSystem.open(name, create);
	if (file == null) return -1;

	fileDiscriptors[fd] = file;

	return fd;
    }

    private int handleReadWrite(int fd, int bufferAddr, int size, boolean read) {
	if (fd < 0 || fd > fileDiscriptors.length || size < 0 || bufferAddr < 0 || bufferAddr >= numPages * pageSize)
	    return -1;

	OpenFile file = fileDiscriptors[fd];
	if (file == null) return -1;

	if (size == 0) return 0;

	int bytesProcessed = 0;

	// copy by pages. Note that a page of data here may span over two virtual pages
	for (int vaddr = bufferAddr; vaddr < bufferAddr + size; vaddr += pageSize) {
	    int vlength = Math.min(pageSize, size - bytesProcessed);
	    byte[] buf = new byte[vlength];
	    int readBytes = 0;
	    int writeBytes = 0;
	    if (read) {
		/* Advances the file pointer by this amount */
		readBytes = file.read(buf, 0, vlength);
		if (readBytes == -1) return -1;
		writeBytes = writeVirtualMemory(vaddr, buf, 0, readBytes);
		if (writeBytes != readBytes) return -1;
		bytesProcessed += readBytes;
	    } else {
		readBytes = readVirtualMemory(vaddr, buf, 0, vlength);
		if (readBytes == -1) return -1;
		writeBytes = file.write(buf, 0, readBytes);
		if (writeBytes != readBytes) return -1;
		bytesProcessed += writeBytes;
	    }
        }

	return bytesProcessed;
    }
    
    private int handleClose(int fd) {
	if (fd < 0 || fd > fileDiscriptors.length) return -1;

	OpenFile file = fileDiscriptors[fd];

	if (file == null) return -1;
	file.close();
	
	fileDiscriptors[fd] = null;

	return 0;
    }
    
    private int handleUnlink(int nameAddr) {
	String fileName = readVirtualMemoryString(nameAddr, 256);
	if (fileName == null) return -1;

	if (UserKernel.fileSystem.remove(fileName))
	    return 0;

	return -1;
    }

    private int handleExec(int nameAddr, int argc, int argvAddr) {
	if (argc < 0) return -1;
	String name = readVirtualMemoryString(nameAddr, 256);
	if (name == null || !name.toLowerCase().endsWith(".coff")) return -1;

	String[] args = new String[argc];
	byte[] argAddrBytes = new byte[4];

	for (int i = 0; i < argc; i++) {
	    if (readVirtualMemory(argvAddr + 4 * i, argAddrBytes) != 4)
		return -1;
	    int argAddr = Lib.bytesToInt(argAddrBytes, 0);
	    String arg = readVirtualMemoryString(argAddr, 256);
	    if (arg == null) return -1;
	    args[i] = arg;
	}

	UserProcess child = UserProcess.newUserProcess();
	child.setParentProcess(this);
	if (child == null || !child.execute(name, args)) return -1;
	int childPID = child.getProcessID();
	childrenProcess.put(childPID, child);

	return childPID;
    }

    private int handleJoin(int processID, int statusAddr) {
	UserProcess childProcess = childrenProcess.get(processID);

	if (childProcess == null || childProcess.getThread() != null)
	    return -1;

	childProcess.getThread().join();

	if (!processesExitStatus.containsKey(pid))
	    return -1;
	int childStatus = processesExitStatus.get(pid);

	if (writeVirtualMemory(statusAddr, Lib.bytesFromInt(childStatus)) != 4)
	    return -1;

	childrenProcess.remove(pid);

	if (processesWithUnhandledException.contains(pid))
	    return 0;

	return 1;
    }
    
    private int handleExit(int status) {
	for (int childPID : childrenProcess.keySet())
	    childrenProcess.get(childPID).setParentProcess(null);

	childrenProcess.clear();
	unloadSections();
	for (int fd = 0; fd < fileDiscriptors.length; fd++)
	    handleClose(fd);

	processLock.acquire();
	numOfRunningProcesses--;
	if (numOfRunningProcesses == 0)
	processesExitStatus.put(pid, status);
	if (numOfRunningProcesses == 0)
	    Kernel.kernel.terminate();
	processLock.release();

	KThread.finish();

	Lib.assertNotReached("KThread.finish() did not make the thread of the process sleep");

	return 0;
    }

    private static final int
        syscallHalt = 0,
	syscallExit = 1,
	syscallExec = 2,
	syscallJoin = 3,
	syscallCreate = 4,
	syscallOpen = 5,
	syscallRead = 6,
	syscallWrite = 7,
	syscallClose = 8,
	syscallUnlink = 9;

    /**
     * Handle a syscall exception. Called by <tt>handleException()</tt>. The
     * <i>syscall</i> argument identifies which syscall the user executed:
     *
     * <table>
     * <tr><td>syscall#</td><td>syscall prototype</td></tr>
     * <tr><td>0</td><td><tt>void halt();</tt></td></tr>
     * <tr><td>1</td><td><tt>void exit(int status);</tt></td></tr>
     * <tr><td>2</td><td><tt>int  exec(char *name, int argc, char **argv);
     * 								</tt></td></tr>
     * <tr><td>3</td><td><tt>int  join(int pid, int *status);</tt></td></tr>
     * <tr><td>4</td><td><tt>int  creat(char *name);</tt></td></tr>
     * <tr><td>5</td><td><tt>int  open(char *name);</tt></td></tr>
     * <tr><td>6</td><td><tt>int  read(int fd, char *buffer, int size);
     *								</tt></td></tr>
     * <tr><td>7</td><td><tt>int  write(int fd, char *buffer, int size);
     *								</tt></td></tr>
     * <tr><td>8</td><td><tt>int  close(int fd);</tt></td></tr>
     * <tr><td>9</td><td><tt>int  unlink(char *name);</tt></td></tr>
     * </table>
     * 
     * @param	syscall	the syscall number.
     * @param	a0	the first syscall argument.
     * @param	a1	the second syscall argument.
     * @param	a2	the third syscall argument.
     * @param	a3	the fourth syscall argument.
     * @return	the value to be returned to the user.
     */
    public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
	switch (syscall) {
	case syscallHalt:
	    return handleHalt();
	case syscallCreate:
	    return handleOpen(a0, true);
	case syscallOpen:
	    return handleOpen(a0, false);
	case syscallRead:
	    return handleReadWrite(a0, a1, a2, true);
	case syscallWrite:
	    return handleReadWrite(a0, a1, a2, false);
	case syscallClose:
	    return handleClose(a0);
	case syscallUnlink:
	    return handleUnlink(a0);
	case syscallExec:
	    return handleExec(a0, a1, a2);
	case syscallJoin:
	    return handleJoin(a0, a1);
	case syscallExit:
	    return handleExit(a0);

	default:
	    Lib.debug(dbgProcess, "Unknown syscall " + syscall);
	    processesWithUnhandledException.add(pid);
	    //Lib.assertNotReached("Unknown system call!");
	    handleExit(-1);
	}
	return 0;
    }

    /**
     * Handle a user exception. Called by
     * <tt>UserKernel.exceptionHandler()</tt>. The
     * <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param	cause	the user exception that occurred.
     */
    public void handleException(int cause) {
	Processor processor = Machine.processor();

	switch (cause) {
	case Processor.exceptionSyscall:
	    int result = handleSyscall(processor.readRegister(Processor.regV0),
				       processor.readRegister(Processor.regA0),
				       processor.readRegister(Processor.regA1),
				       processor.readRegister(Processor.regA2),
				       processor.readRegister(Processor.regA3)
				       );
	    processor.writeRegister(Processor.regV0, result);
	    processor.advancePC();
	    break;				       
				       
	default:
	    Lib.debug(dbgProcess, "Unexpected exception: " +
		      Processor.exceptionNames[cause]);
	    Lib.assertNotReached("Unexpected exception");
	}
    }

    public int getProcessID() {
	return pid;
    }

    public UThread getThread() {
	return thread;
    }

    public UserProcess getParentProcess() {
	return parentProcess;
}

    public void setParentProcess(UserProcess parentProcess) {
	this.parentProcess = parentProcess;
    }

/** The program being run by this process. */
    protected Coff coff;

    /** This process's page table. */
    protected TranslationEntry[] pageTable;
    /** The number of contiguous pages occupied by the program. */
    protected int numPages;

    /** The number of pages in the program's stack. */
    protected final int stackPages = 8;

    private int initialPC, initialSP;
    private int argc, argv;

    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';

    private int pid;
    private static int nextPid = 1;
    private static int numOfRunningProcesses = 0;
    private static Lock processLock = new Lock();
    private UThread thread;

    private OpenFile[] fileDiscriptors = new OpenFile[16];

    /** Should i use ConcurrentHashMap?? */
    private HashMap<Integer, UserProcess> childrenProcess;
    private UserProcess parentProcess = null;
    private static HashMap<Integer, Integer> processesExitStatus;
    private HashSet<Integer> processesWithUnhandledException;
}