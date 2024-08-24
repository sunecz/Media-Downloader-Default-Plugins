package sune.app.mediadown.downloader.wms.common;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import sune.app.mediadown.InternalState;
import sune.app.mediadown.TaskStates;
import sune.app.mediadown.concurrent.SyncObject;
import sune.app.mediadown.download.DownloadContext;
import sune.app.mediadown.event.tracker.DownloadTracker;
import sune.app.mediadown.event.tracker.TrackerManager;
import sune.app.mediadown.media.MediaConstants;

public final class DownloaderState {
	
	private final int maxRetryAttempts;
	private final int waitOnRetryMs;
	private final int numOfWorkers;
	private final TrackerManager trackerManager;
	
	private final Object lock = new Object();
	private final InternalState state = new InternalState();
	private final SyncObject lockPause = new SyncObject();
	private final AtomicLong size = new AtomicLong(MediaConstants.UNKNOWN_SIZE);
	private final Map<DownloadContext, AtomicLong> lastSizes = new WeakHashMap<>();
	private final AtomicBoolean propagateError = new AtomicBoolean(true);
	private final AtomicReference<Exception> exception = new AtomicReference<>();
	private volatile DownloadTracker downloadTracker;
	
	public DownloaderState(
		int maxRetryAttempts,
		int waitOnRetryMs,
		int numOfWorkers,
		TrackerManager trackerManager
	) {
		this.maxRetryAttempts = maxRetryAttempts;
		this.waitOnRetryMs = waitOnRetryMs;
		this.numOfWorkers = numOfWorkers;
		this.trackerManager = Objects.requireNonNull(trackerManager);
	}
	
	private static final AtomicLong newLastSize(DownloadContext context) {
		return new AtomicLong();
	}
	
	public final DownloadTracker downloadTracker() {
		DownloadTracker tracker;
		if((tracker = downloadTracker) == null) {
			tracker = new DownloadTracker(MediaConstants.UNKNOWN_SIZE);
			downloadTracker = tracker;
		}
		
		return tracker;
	}
	
	public final boolean check() {
		// Wait for resume, if paused
		if(state.is(TaskStates.PAUSED)) {
			lockPause.await();
		}
		
		// If already not running, do not continue
		return state.is(TaskStates.RUNNING);
	}
	
	public final void sizeSet(long value) {
		size.set(value);
		downloadTracker().updateTotal(value);
	}
	
	public final void alterTotalSize(long value) {
		long current = size.addAndGet(value);
		downloadTracker().updateTotal(current);
	}
	
	public final void alterDownloadedSize(long value) {
		downloadTracker().update(-value);
	}
	
	public void set(int value) {
		state.set(value);
	}
	
	public void unset(int value) {
		int prev = state.get();
		state.unset(value);
		
		if((prev & TaskStates.PAUSED) != 0
				&& (value & TaskStates.PAUSED) != 0) {
			lockPause.unlock();
		}
	}
	
	public void reset(int value) {
		state.clear(value);
		lockPause.unlock();
		size.set(MediaConstants.UNKNOWN_SIZE);
		downloadTracker = null;
		lastSizes.clear();
		propagateError.set(true);
		exception.set(null);
	}
	
	public boolean is(int value) {
		return state.is(value);
	}
	
	public long sizeGet() {
		return size.get();
	}
	
	public void onUpdate(DownloadContext context) {
		DownloadTracker tracker = (DownloadTracker) context.trackerManager().tracker();
		long current = tracker.current();
		
		AtomicLong lastSize;
		synchronized(lock) {
			lastSize = lastSizes.computeIfAbsent(context, DownloaderState::newLastSize);
		}
		
		long delta = current - lastSize.get();
		downloadTracker.update(delta);
		lastSize.set(current);
	}
	
	public void onError(DownloadContext context) {
		if(!propagateError.get()) {
			return; // Ignore the error, if needed (used for retries)
		}
		
		exception.set(context.exception());
		state.set(TaskStates.ERROR);
	}
	
	public void setPropagateError(boolean value) {
		propagateError.set(value);
	}
	
	public void setException(Exception ex) {
		exception.set(ex);
	}
	
	public Exception exception() {
		return exception.get();
	}
	
	public int maxRetryAttempts() {
		return maxRetryAttempts;
	}
	
	public int waitOnRetryMs() {
		return waitOnRetryMs;
	}
	
	public int numOfWorkers() {
		return numOfWorkers;
	}
	
	public TrackerManager trackerManager() {
		return trackerManager;
	}
}