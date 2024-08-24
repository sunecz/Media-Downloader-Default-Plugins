package sune.app.mediadown.downloader.wms.common;

import java.net.URI;

public interface RemoteFile {
	
	Object value();
	URI uri();
	void size(long size);
	long size();
	void estimatedSize(long size);
	long estimatedSize();
}