package sune.app.mediadown.downloader.wms.parallel;

import java.nio.file.Path;

import sune.app.mediadown.downloader.wms.common.RemoteFile;

interface SerialDownloader extends AutoCloseable {
	
	boolean downloadSegment(RemoteFile segment, Path output) throws Exception;
	void addWritten(long value);
	long written();
}