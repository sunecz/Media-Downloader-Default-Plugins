package sune.app.mediadown.downloader.wms.common;

import java.net.URI;
import java.util.Objects;

import sune.app.mediadown.download.segment.FileSegment;

public final class RemoteFileSegment implements RemoteFile {
	
	private final int index;
	private final FileSegment segment;
	private long size;
	private long estimatedSize;
	
	public RemoteFileSegment(int index, FileSegment segment) {
		this.index = index;
		this.segment = Objects.requireNonNull(segment);
		this.size = segment.size();
		this.estimatedSize = 0L;
	}
	
	public int index() { return index; }
	@Override public FileSegment value() { return segment; }
	@Override public URI uri() { return segment.uri(); }
	@Override public void size(long size) { this.size = this.estimatedSize = size; }
	@Override public long size() { return size; }
	@Override public void estimatedSize(long size) { this.estimatedSize = size; }
	@Override public long estimatedSize() { return estimatedSize; }
}