package nachos.threads;

import nachos.machine.*;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.Iterator;

/**
 * A scheduler that chooses threads based on their priorities.
 *
 * <p>
 * A priority scheduler associates a priority with each thread. The next thread
 * to be dequeued is always a thread with priority no less than any other
 * waiting thread's priority. Like a round-robin scheduler, the thread that is
 * dequeued is, among all the threads of the same (highest) priority, the
 * thread that has been waiting longest.
 *
 * <p>
 * Essentially, a priority scheduler gives access in a round-robin fassion to
 * all the highest-priority threads, and ignores all other threads. This has
 * the potential to
 * starve a thread if there's always a thread waiting with higher priority.
 *
 * <p>
 * A priority scheduler must partially solve the priority inversion problem; in
 * particular, priority must be donated through locks, and through joins.
 */
public class PriorityScheduler extends Scheduler {
    /**
     * Allocate a new priority scheduler.
     */
    public PriorityScheduler() {
    }
    
    /**
     * Allocate a new priority thread queue.
     *
     * @param	transferPriority	<tt>true</tt> if this queue should
     *					transfer priority from waiting threads
     *					to the owning thread.
     * @return	a new priority thread queue.
     */
    public ThreadQueue newThreadQueue(boolean transferPriority) {
	return new PriorityQueue(transferPriority);
    }

    public int getPriority(KThread thread) {
	Lib.assertTrue(Machine.interrupt().disabled());
		       
	return getThreadState(thread).getPriority();
    }

    public int getEffectivePriority(KThread thread) {
	Lib.assertTrue(Machine.interrupt().disabled());
		       
	return getThreadState(thread).getEffectivePriority();
    }

    public void setPriority(KThread thread, int priority) {
	Lib.assertTrue(Machine.interrupt().disabled());
		       
	Lib.assertTrue(priority >= priorityMinimum &&
		   priority <= priorityMaximum);
	
	getThreadState(thread).setPriority(priority);
    }

    public boolean increasePriority() {
	boolean intStatus = Machine.interrupt().disable();
		       
	KThread thread = KThread.currentThread();

	int priority = getPriority(thread);
	if (priority == priorityMaximum)
	    return false;

	setPriority(thread, priority+1);

	Machine.interrupt().restore(intStatus);
	return true;
    }

    public boolean decreasePriority() {
	boolean intStatus = Machine.interrupt().disable();
		       
	KThread thread = KThread.currentThread();

	int priority = getPriority(thread);
	if (priority == priorityMinimum)
	    return false;

	setPriority(thread, priority-1);

	Machine.interrupt().restore(intStatus);
	return true;
    }

    /**
     * The default priority for a new thread. Do not change this value.
     */
    public static final int priorityDefault = 1;
    /**
     * The minimum priority that a thread can have. Do not change this value.
     */
    public static final int priorityMinimum = 0;
    /**
     * The maximum priority that a thread can have. Do not change this value.
     */
    public static final int priorityMaximum = 7;

    /**
     * Return the scheduling state of the specified thread.
     *
     * @param	thread	the thread whose scheduling state to return.
     * @return	the scheduling state of the specified thread.
     */
    protected ThreadState getThreadState(KThread thread) {
	if (thread.schedulingState == null)
	    thread.schedulingState = new ThreadState(thread);

	return (ThreadState) thread.schedulingState;
    }

    /**
     * A <tt>ThreadQueue</tt> that sorts threads by priority.
     */
    protected class PriorityQueue extends ThreadQueue {
	PriorityQueue(boolean transferPriority) {
	    this.transferPriority = transferPriority;
	}

	public void waitForAccess(KThread thread) {
	    Lib.assertTrue(Machine.interrupt().disabled());
	    ThreadState threadState = getThreadState(thread);
	    threadState.waitForAccess(this);
	}

	public void acquire(KThread thread) {
	    Lib.assertTrue(Machine.interrupt().disabled());
	    Lib.assertTrue(owner == null);
	    getThreadState(thread).acquire(this);
	}

	/**
	 * Return the next thread that dominates the priority queue, and
	 * recalculate all priorities.
	 *
	 * @return	the next thread that dominates the priority queue
	 */
	public KThread nextThread() {
	    Lib.assertTrue(Machine.interrupt().disabled());

	    if (owner != null) {
		ThreadState oldOwnerState = getThreadState(owner);
		/** remove current resource(implemented as priority queue from the resource list of olderOwner)*/
		oldOwnerState.resources.remove(this);
		owner = null;
	    }
	    
	    ThreadState next = pickNextThread();

	    if (next != null) {
		pqWaitQueue.remove(next);
		next.waitQueue = null;
		next.acquire(this);
		setDirty();
	    }

	    return owner;
	}

	/**
	 * Return the next thread that <tt>nextThread()</tt> would return,
	 * without modifying the state of this queue.
	 *
	 * @return	the next thread that <tt>nextThread()</tt> would
	 *		return.
	 */
	protected ThreadState pickNextThread() {
	    ThreadState nextThreadState = null;

	    for (Iterator<ThreadState> ts = pqWaitQueue.iterator(); ts.hasNext();) {
		ThreadState state = ts.next();
		int priority = state.getEffectivePriority();

		if (nextThreadState == null || priority > nextThreadState.getEffectivePriority())
		    nextThreadState = state;
	    }

	    return nextThreadState;
	}
	
	public int getEffectivePriority() {
	    if (transferPriority == false)
		return priorityMinimum;

	    if (dirty) {
		effectivePriority = priorityMinimum;
		long longestInsertTime = Long.MAX_VALUE;
		for (ThreadState threadState : pqWaitQueue) {
		    int priority = threadState.getEffectivePriority();
		    long insertTime = threadState.insertTime;
		    if (priority > effectivePriority || (priority == effectivePriority && insertTime < longestInsertTime)) {
			effectivePriority = priority;
			longestInsertTime = insertTime;
		    }
		}
		dirty = false;
	    }

	    return effectivePriority;
	}

	public void setDirty() {
	    if (transferPriority == false)
		return;

	    dirty = true;

	    if (owner != null)
		getThreadState(owner).setDirty();
	}

	public void print() {
	    Lib.assertTrue(Machine.interrupt().disabled());
	    // implement me (if you want)
	}

	/**
	 * <tt>true</tt> if this queue should transfer priority from waiting
	 * threads to the owning thread.
	 */
	public boolean transferPriority;
	/** the owner of this priority queue */
	protected KThread owner = null;
	/** a timer counter of the waiting thread */
	protected int timeCounter = 0;
	/** 
	 * Set to true when a new thread is added to the queue, or
         * any of the thread in the pqWaitQueue is marked dirty 
         */
	protected boolean dirty = false;
	/**
	 * The cached highest of the effective priorities in the waitQueue, excluding owner 
	 * This value is invalidated while dirty is true
	 */
	protected int effectivePriority = priorityMinimum;
	/** core part implementing a priority queue using ThreadState as comparable element */
	protected LinkedList<ThreadState> pqWaitQueue = new LinkedList<ThreadState>();
    }

    /**
     * The scheduling state of a thread. This should include the thread's
     * priority, its effective priority, any objects it owns, and the queue
     * it's waiting for, if any.
     *
     * @see	nachos.threads.KThread#schedulingState
     */
    protected class ThreadState {
	/**
	 * Allocate a new <tt>ThreadState</tt> object and associate it with the
	 * specified thread.
	 *
	 * @param	thread	the thread this state belongs to.
	 */
	public ThreadState(KThread thread) {
	    this.thread = thread;
	    this.resources = new ArrayList<ThreadQueue>();
	    this.priority = priorityDefault;
	    this.effectivePriority = priorityDefault;
	    this.waitQueue = null;
	    this.dirty = false;
	}

	/**
	 * Return the priority of the associated thread.
	 *
	 * @return	the priority of the associated thread.
	 */
	public int getPriority() {
	    return priority;
	}

	/**
	 * Return the effective priority of the associated thread.
	 *
	 * @return	the effective priority of the associated thread.
	 */
	public int getEffectivePriority() {
	    if (dirty == false)
		return effectivePriority;

	    effectivePriority = priority;
	    for(ThreadQueue queue : resources) {
		int maxQueueEffective  = ((PriorityQueue)queue).getEffectivePriority();
		if (effectivePriority < maxQueueEffective)
		    effectivePriority = maxQueueEffective;
	    }
	    dirty = false;

	    return effectivePriority;
	}

	/**
	 * Set the priority of the associated thread to the specified value.
	 *
	 * @param	priority	the new priority.
	 */
	public void setPriority(int priority) {
	    if (this.priority == priority)
		return;

	    this.priority = priority;

	    setDirty();
	}

	/**
	 * Called when <tt>waitForAccess(thread)</tt> (where <tt>thread</tt> is
	 * the associated thread) is invoked on the specified priority queue.
	 * The associated thread is therefore waiting for access to the
	 * resource guarded by <tt>waitQueue</tt>. This method is only called
	 * if the associated thread cannot immediately obtain access.
	 *
	 * @param	waitQueue	the queue that the associated thread is
	 *				now waiting on.
	 *
	 * @see	nachos.threads.ThreadQueue#waitForAccess
	 */
	public void waitForAccess(PriorityQueue waitQueue) {
	    Lib.assertTrue(Machine.interrupt().disabled());
	    Lib.assertTrue(this.waitQueue == null);
	    insertTime = waitQueue.timeCounter;
	    waitQueue.pqWaitQueue.add(this);
	    this.waitQueue = waitQueue;
	    waitQueue.timeCounter++;
	    waitQueue.setDirty();
	}

	/**
	 * Called when the associated thread has acquired access to whatever is
	 * guarded by <tt>waitQueue</tt>. This can occur either as a result of
	 * <tt>acquire(thread)</tt> being invoked on <tt>waitQueue</tt> (where
	 * <tt>thread</tt> is the associated thread), or as a result of
	 * <tt>nextThread()</tt> being invoked on <tt>waitQueue</tt>.
	 *
	 * @see	nachos.threads.ThreadQueue#acquire
	 * @see	nachos.threads.ThreadQueue#nextThread
	 */
	public void acquire(PriorityQueue waitQueue) {
	    Lib.assertTrue(Machine.interrupt().disabled());
	    Lib.assertTrue(waitQueue.owner == null);
	    waitQueue.owner = this.thread;
	    resources.add(waitQueue);
	    setDirty();
	}

	/**
	 * Return the insert time of the associated thread.
	 *
	 * @return	the insert time of the associated thread.
	 */
	public long getInsertTime() {
	    return insertTime;
	}

	public void setDirty() {
	    if (dirty)
		return;

	    dirty = true;

	    if (waitQueue != null)
		((PriorityQueue)waitQueue).setDirty();
	}

	/** The thread with which this object is associated. */	   
	protected KThread thread;
	/** The priority of the associated thread. */
	protected int priority;
	/** The effective priority of the associated thread. */
	protected int effectivePriority;
	/** Collection of PriorityQueues this thread currently holds. Multiple resources are allowed */
	protected ArrayList<ThreadQueue> resources;
	/** The resource this is waiting for. A thread can only waits for one resource */
	protected ThreadQueue waitQueue;
	/** If multiple threads with the same highest priority are waiting, the scheduler should choose the one that has been waiting in the queue the longest */
	protected long insertTime;
	/**
	 * Set to true when this thread's priority is changed, or 
	 * when the dirty bit of one of the resources is dirty 
	 */
	private boolean dirty;
    }
}
