package sune.app.mediadown.downloader.wms.common;

import sune.app.mediadown.event.EventBindable;
import sune.app.mediadown.event.tracker.TrackerEvent;

public interface InternalSegmentsDownloader extends AutoCloseable, EventBindable<TrackerEvent> {
	
	boolean download(Segments segments) throws Exception;
}