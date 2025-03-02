package com.pocketsphinxapi.consumer;

import java.util.concurrent.LinkedBlockingQueue;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import edu.cmu.pocketsphinx.Config;
import edu.cmu.pocketsphinx.Decoder;
import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.pocketsphinx;


public class RecognizerTask implements Runnable {

	class AudioTask implements Runnable {

		LinkedBlockingQueue<short[]> q;
		AudioRecord rec;
		int block_size;
		boolean done;

		static final int DEFAULT_BLOCK_SIZE = 512;

		AudioTask() {
			this.init(new LinkedBlockingQueue<short[]>(), DEFAULT_BLOCK_SIZE);
		}

		AudioTask(LinkedBlockingQueue<short[]> q) {
			this.init(q, DEFAULT_BLOCK_SIZE);
		}

		AudioTask(LinkedBlockingQueue<short[]> q, int block_size) {
			this.init(q, block_size);
		}

		void init(LinkedBlockingQueue<short[]> q, int block_size) {
			try
			{
				this.done = false;
				this.q = q;
				this.block_size = block_size;
				this.rec = new AudioRecord(MediaRecorder.AudioSource.MIC, 8000,
						AudioFormat.CHANNEL_IN_MONO,
						AudioFormat.ENCODING_PCM_16BIT, 8192);
			}
			catch (Exception exc)
			{
				String erro = exc.getLocalizedMessage();
			}				
		}

		public int getBlockSize() {
			return block_size;
		}

		public void setBlockSize(int block_size) {
			this.block_size = block_size;
		}

		public LinkedBlockingQueue<short[]> getQueue() {
			return q;
		}

		public void stop() {
			this.done = true;
		}

		public void run() {
			try
			{
				this.rec.startRecording();
				while (!this.done) {
					int nshorts = this.readBlock();
					if (nshorts <= 0)
						break;
				}
				this.rec.stop();
				this.rec.release();
			}
			catch (Exception exc)
			{
				String erro = exc.getLocalizedMessage();
			}
		}

		int readBlock() {
			int nshorts = 0 ;
			try
			{
				short[] buf = new short[this.block_size];
				nshorts = this.rec.read(buf, 0, buf.length);
				if (nshorts > 0) {
					Log.d(getClass().getName(), "Posting " + nshorts + " samples to queue");
					this.q.add(buf);
				}
			}
			catch (Exception exc)
			{
				String erro = exc.getLocalizedMessage();
			}

			return nshorts;
		}
	}

	private Config c; 	
	public Decoder ps;
	AudioTask audio;
	Thread audio_thread;
	LinkedBlockingQueue<short[]> audioq;
	RecognitionListener rl;
	boolean use_partials;
	enum State {
		IDLE, LISTENING
	};
	enum Event {
		NONE, START, STOP, SHUTDOWN
	};

	Event semaphore;
	private boolean resetdecoder = true;
	private GrammarTools gram;

	public RecognitionListener getRecognitionListener() {
		return rl;
	}

	public void setRecognitionListener(RecognitionListener rl) {
		this.rl = rl;
	}

	public void setUsePartials(boolean use_partials) {
		this.use_partials = use_partials;
	}

	public boolean getUsePartials() {
		return this.use_partials;
	}
	
	public void doRecognize()
	{
		//if (this.c == null)
		if (resetdecoder)
			this.configure();
		
		//if  (this.ps == null)
		if (resetdecoder)
		{
			this.resetdecoder = false;						
			this.ps = new Decoder(this.c);
		}	
	}
	
	public RecognizerTask(GrammarTools gram) {
		this.audio = null;
		this.audioq = new LinkedBlockingQueue<short[]>();
		this.use_partials = false;
		this.semaphore = Event.NONE;	
		this.gram = gram;
		
	}

	private void configure() {
			
		this.c = new Config();
		
		// am
		this.c.setString("-hmm",gram.pathhmm);
		
		// LEXICAL MODEL
		this.c.setString("-dict",gram.pathdic);
				
		// LM
		this.c.setString("-jsgf",gram.pathgram);
						
		// LOG FILES 
		//c.setString("-rawlogdir", "/sdcard/Android/data/pocketsphinx");		
		pocketsphinx.setLogfile("/sdcard/Android/data/pocketsphinx/pocketsphinx.log");
		
		this.c.setFloat("-samprate", 8000);
		this.c.setInt("-maxhmmpf", 2000);
		this.c.setInt("-maxwpf", 10);
		this.c.setInt("-pl_window", 2);
		this.c.setBoolean("-backtrace", true);		
		this.c.setBoolean("-bestpath", false); 		
	}

	public void run() {

		boolean done = false;

		State state = State.IDLE;
		
		String partial_hyp = null;
		
		while (!done) {
		
			Event todo = Event.NONE;
			synchronized (this.semaphore) {
				todo = this.semaphore;
		
				if (state == State.IDLE && todo == Event.NONE) {
					try {
						Log.d(getClass().getName(), "waiting");
						this.semaphore.wait();
						todo = this.semaphore;
						Log.d(getClass().getName(), "got" + todo);
					} catch (InterruptedException e) {
		
						Log.e(getClass().getName(), "Interrupted waiting for mailbox, shutting down");
						todo = Event.SHUTDOWN;
					}
				}
				
				this.semaphore = Event.NONE;
			}
			
			switch (todo) {
			case NONE:
				if (state == State.IDLE)
					Log.e(getClass().getName(), "Received NONE in semaphore when IDLE, threading error?");
				break;
			case START:
				if (state == State.IDLE) { 
					Log.d(getClass().getName(), "START");
					this.audio = new AudioTask(this.audioq, 1024);
					this.audio_thread = new Thread(this.audio);
					this.ps.startUtt();
					this.audio_thread.start();
					state = State.LISTENING;
				}
				else
					Log.e(getClass().getName(), "Received START in semaphore when LISTENING");
				break;
			case STOP:
				if (state == State.IDLE)
					Log.e(getClass().getName(), "Received STOP in semaphore when IDLE");
				else {
					Log.d(getClass().getName(), "STOP");
					assert this.audio != null;
					this.audio.stop();
					try {
						this.audio_thread.join();
					}
					catch (InterruptedException e) {
						Log.e(getClass().getName(), "Interrupted waiting for audio thread, shutting down");
						done = true;
					}
					
					short[] buf;
					while ((buf = this.audioq.poll()) != null) {
						Log.i(getClass().getName(), "Reading " + buf.length + " samples from queue");
						this.ps.processRaw(buf, buf.length, false, false);
					}
					this.ps.endUtt();
					this.audio = null;
					this.audio_thread = null;
					Hypothesis hyp = this.ps.getHyp();
					if (this.rl != null) {
						if (hyp == null) {
							Log.d(getClass().getName(), "Recognition failure");
							this.rl.onError(-1);
						}
						else {
							Bundle b = new Bundle();
							Log.d(getClass().getName(), "Final hypothesis: " + hyp.getHypstr());
							b.putString("hyp", hyp.getHypstr());
							this.rl.onResults(b);
						}
					}
					state = State.IDLE;
				}
				break;
			case SHUTDOWN:
				Log.d(getClass().getName(), "SHUTDOWN");
				if (this.audio != null) {
					this.audio.stop();
					assert this.audio_thread != null;
					try {
						this.audio_thread.join();
					}
					catch (InterruptedException e) {
						
					}
				}
				this.ps.endUtt();
				this.audio = null;
				this.audio_thread = null;
				state = State.IDLE;
				done = true;
				break;
			}
			
			if (state == State.LISTENING) {
				assert this.audio != null;
				try {
					short[] buf = this.audioq.take();
					Log.d(getClass().getName(), "Reading " + buf.length + " samples from queue");
					this.ps.processRaw(buf, buf.length, false, false);
					Hypothesis hyp = this.ps.getHyp();
					if (hyp != null) {
						String hypstr = hyp.getHypstr();
						if (hypstr != partial_hyp) {
							Log.d(getClass().getName(), "Hypothesis: " + hyp.getHypstr());
							if (this.rl != null && hyp != null) {
								Bundle b = new Bundle();
								b.putString("hyp", hyp.getHypstr());
								this.rl.onPartialResults(b);
							}
						}
						partial_hyp = hypstr;
					}
				} catch (InterruptedException e) {
					Log.d(getClass().getName(), "Interrupted in audioq.take");
				}
			}
		}
	}

	public void start() {
		Log.d(getClass().getName(), "signalling START");
		synchronized (this.semaphore) {
			this.semaphore.notifyAll();
			Log.d(getClass().getName(), "signalled START");
			this.semaphore = Event.START;
		}
	}

	public void stop() {
		Log.d(getClass().getName(), "signalling STOP");
		synchronized (this.semaphore) {
			this.semaphore.notifyAll();
			Log.d(getClass().getName(), "signalled STOP");
			this.semaphore = Event.STOP;
		}
	}

	public void shutdown() {
		Log.d(getClass().getName(), "signalling SHUTDOWN");
		synchronized (this.semaphore) {
			this.semaphore.notifyAll();
			Log.d(getClass().getName(), "signalled SHUTDOWN");
			this.semaphore = Event.SHUTDOWN;
		}
	}

	
	public void Reset() 
	{		
		if (this.ps != null)
		{
			this.ps.delete();			
		}
	
		if (this.c != null)
		{			
			//this.c.delete();					
		}		
		
		resetdecoder = true;
		this.ps = null;
		this.c = null;
		System.gc();
	}
}