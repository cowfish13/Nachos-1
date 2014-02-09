package nachos.threads;

import nachos.machine.*;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>,
 * and multiple threads can be waiting to <i>listen</i>. But there should never
 * be a time when both a speaker and a listener are waiting, because the two
 * threads can be paired off at this point.
 */
public class Communicator {
    /* this is the word to be listened by listened
     * the words from other speakers are hidden and
     * put to this variable when the owner(speaker) are wakened
     */
    private int wordToBeListened;

    /*
     * if this varialbe is turned on, then the newly coming speaker
     * goes to sleep
     **/
    private boolean isWordToBeListened;


    private Condition2 speakerCondition;
    private Condition2 listenerCondition;

    /*
     * this condition variable is used to pair a speaker and a listener
     * */
    private Condition2 listenerSpeakerHandShake;
    // private handshake

    private Lock lock;
    /**
     * Allocate a new communicator.
     */
    public Communicator() {
	this.isWordToBeListened = false;
	this.lock = new Lock();
	this.speakerCondition = new Condition2(lock);
	this.listenerCondition = new Condition2(lock);
	this.listenerSpeakerHandShake = new Condition2(lock);
    }

    /**
     * Wait for a thread to listen through this communicator, and then transfer
     * <i>word</i> to the listener.
     *
     * <p>
     * Does not return until this thread is paired up with a listening thread.
     * Exactly one listener should receive <i>word</i>.
     *
     * @param	word	the integer to transfer.
     */
    public void speak(int word) {
	lock.acquire();
	while(isWordToBeListened)
	    speakerCondition.sleep();

	this.isWordToBeListened = true;
	this.wordToBeListened = word;

	/*
	 * try to wake up one listener if there is one
	 * but what if on listener?
	 * well, that is why listenerSpeakerHandShake is present
	 **/
	listenerCondition.wake();

	/*
	 * 1. if there is no listener
	 *    goes to sleep in listenerSpeakerHandShake
	 *
	 * 2. if there is at least one listener
	 *    still goes to sleep in listenerSpeakerHandShake
	 *    however, at least one listener is in ready queue due to 
	 *
	 *    listenerCondition.wake()
	 *
	 *    that listener will immediately wake this speaker up
	 **/
	listenerSpeakerHandShake.sleep();

	lock.release();
    }

    /**
     * Wait for a thread to speak through this communicator, and then return
     * the <i>word</i> that thread passed to <tt>speak()</tt>.
     *
     * @return	the integer transferred.
     */    
    public int listen() {
	int wordTransferred;
	lock.acquire();
	while(!isWordToBeListened)
	    listenerCondition.sleep();

	wordTransferred = this.wordToBeListened;
	this.isWordToBeListened = false;

	/*
	 * wake up the speaker who shout `word` out
	 **/
	speakerCondition.wake();
	listenerSpeakerHandShake.wake();

	lock.release();

	return wordTransferred;
    }
}
