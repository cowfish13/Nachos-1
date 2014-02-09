package nachos.threads;

import nachos.machine.*;

import java.util.PriorityQueue;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
    private PriorityQueue<ThreadTimeTuple> waitQueue;
    /**
     * Allocate a new Alarm. Set the machine's timer interrupt handler to this
     * alarm's callback.
     *
     * <p><b>Note</b>: Nachos will not function correctly with more than one
     * alarm.
     */
    public Alarm() {
	this.waitQueue = new PriorityQueue<ThreadTimeTuple>();
	Machine.timer().setInterruptHandler(new Runnable() {
		public void run() { timerInterrupt(); }
	    });
    }

    /**
     * The timer interrupt handler. This is called by the machine's timer
     * periodically (approximately every 500 clock ticks). Causes the current
     * thread to yield, forcing a context switch if there is another thread
     * that should be run.
     */
    public void timerInterrupt() {
	long currentTime = Machine.timer().getTime();
	boolean wakeMoreThread = true;
	while(wakeMoreThread && waitQueue.peek() != null) {
	    if(waitQueue.peek().getTime() < currentTime) {
		waitQueue.poll().getThread().ready();
	    } else {
		wakeMoreThread = false;
	    }
	}
	KThread.yield();
    }

    /**
     * Put the current thread to sleep for at least <i>x</i> ticks,
     * waking it up in the timer interrupt handler. The thread must be
     * woken up (placed in the scheduler ready set) during the first timer
     * interrupt where
     *
     * <p><blockquote>
     * (current time) >= (WaitUntil called time)+(x)
     * </blockquote>
     *
     * @param	x	the minimum number of clock ticks to wait.
     *
     * @see	nachos.machine.Timer#getTime()
     */
    public void waitUntil(long x) {
	boolean intStatus = Machine.interrupt().disable();
	long wakeTime = Machine.timer().getTime() + x;
	waitQueue.add(new ThreadTimeTuple(KThread.currentThread(), wakeTime));
	KThread.sleep();
	Machine.interrupt().restore(intStatus);
    }

    private class ThreadTimeTuple implements Comparable<ThreadTimeTuple> {
	private KThread thread;
	private long waitTime;

	ThreadTimeTuple(KThread thread, long waitTime) {
	    this.thread = thread;
	    this.waitTime = waitTime;
	}

	@Override
	public int compareTo(ThreadTimeTuple other) {
	    if (this.waitTime > other.waitTime)	return 1;
	    if (this.waitTime < other.waitTime) return -1;
	    return 0;
	}
	
	public long getTime() {return this.waitTime;}
	public KThread getThread() {return this.thread;}
    }
}
