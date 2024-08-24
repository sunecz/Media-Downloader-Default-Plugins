package sune.app.mediadown.downloader.wms.common;

import sune.app.mediadown.event.tracker.Tracker;

public interface TimeUpdateTrackerBase extends Tracker {
	
	void timeMs(long timeMs);
	void totalTimeMs(long totalTimeMs);
}