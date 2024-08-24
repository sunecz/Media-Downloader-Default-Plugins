package sune.app.mediadown.downloader.wms.common;

public interface DownloadRetry {
	
	long waitTime(int attempt);
	boolean waitForRetry(int attempt, long waitTime, DownloadRetryTracker tracker);
	
	default boolean waitForRetry(int attempt, DownloadRetryTracker tracker) {
		return waitForRetry(attempt, waitTime(attempt), tracker);
	}
}