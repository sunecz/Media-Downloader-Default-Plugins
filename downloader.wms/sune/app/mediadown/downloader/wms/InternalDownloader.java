package sune.app.mediadown.downloader.wms;

import java.nio.file.Path;

import sune.app.mediadown.Download;
import sune.app.mediadown.event.DownloadEvent;
import sune.app.mediadown.event.EventBindable;
import sune.app.mediadown.event.EventCallable;
import sune.app.mediadown.event.tracker.DownloadTracker;
import sune.app.mediadown.util.Web.Request;

public interface InternalDownloader extends EventBindable<DownloadEvent>, EventCallable<DownloadEvent>, HasTaskState {
	
	long start(Download download, Request request, Path output, DownloadConfiguration configuration) throws Exception;
	void pause() throws Exception;
	void resume() throws Exception;
	void stop() throws Exception;
	
	void setTracker(DownloadTracker tracker);
	
	Request request();
	Path output();
	DownloadConfiguration configuration();
}