package sune.app.mediadown.downloader.wms.parallel;

import sune.app.mediadown.download.Destination;
import sune.app.mediadown.downloader.wms.common.RemoteFile;

interface SerialDownloader extends AutoCloseable {
	
	boolean downloadSegment(RemoteFile segment, Destination destination) throws Exception;
	void addWritten(long value);
	long written();
}