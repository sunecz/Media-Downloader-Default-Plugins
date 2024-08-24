package sune.app.mediadown.downloader.wms.common;

import java.util.Objects;

public final class SimpleDownloadRetry implements DownloadRetry {
	
	private static final long TIME_UPDATE_RESOLUTION_MS = 50L;
	
	private final DownloaderState state;
	
	public SimpleDownloadRetry(DownloaderState state) {
		this.state = Objects.requireNonNull(state);
	}
	
	private final void waitMs(long ms) {
		if(ms <= 0L) {
			return;
		}
		
		try {
			Thread.sleep(ms);
		} catch(InterruptedException ex) {
			// Ignore
		}
	}
	
	private final void waitMs(long ms, DownloadRetryTracker tracker) {
		if(ms <= 0L) {
			return;
		}
		
		// Update the tracker, so it is visible to the user
		tracker.totalTimeMs(ms);
		
		// Optimization for low wait values
		if(ms <= TIME_UPDATE_RESOLUTION_MS) {
			try {
				Thread.sleep(ms);
			} catch(InterruptedException ex) {
				// Ignore
			}
			
			return; // No need to continue
		}
		
		for(long first  = System.nanoTime(),
				 target = ms * 1000000L,
				 time;
				(time = System.nanoTime() - first) < target;) {
			// Make the loop pausable and stoppable
			if(!state.check()) break;
			
			// Update the tracker, so it is visible to the user
			tracker.timeMs(time / 1000000L);
			
			try {
				Thread.sleep(TIME_UPDATE_RESOLUTION_MS);
			} catch(InterruptedException ex) {
				break;
			}
		}
	}
	
	@Override
	public long waitTime(int attempt) {
		return (long) (state.waitOnRetryMs() * Math.pow(attempt, 4.0 / 3.0));
	}
	
	@Override
	public boolean waitForRetry(int attempt, long waitTime, DownloadRetryTracker tracker) {
		if(tracker != null) {
			tracker.attempt(attempt);
			waitMs(waitTime, tracker);
			tracker.timeMs(-1L);
		} else {
			waitMs(waitTime);
		}
		
		return state.check();
	}
}