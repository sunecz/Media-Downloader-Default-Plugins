package sune.app.mediadown.downloader.wms.common;

public interface DownloadRetryTracker extends TimeUpdateTrackerBase {
	
	void attempt(int attempt);
}