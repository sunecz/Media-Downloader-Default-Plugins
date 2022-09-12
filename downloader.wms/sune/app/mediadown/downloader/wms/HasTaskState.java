package sune.app.mediadown.downloader.wms;

public interface HasTaskState {
	
	boolean isRunning();
	boolean isDone();
	boolean isStarted();
	boolean isPaused();
	boolean isStopped();
	boolean isError();
}