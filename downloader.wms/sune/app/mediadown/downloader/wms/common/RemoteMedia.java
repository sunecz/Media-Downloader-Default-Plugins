package sune.app.mediadown.downloader.wms.common;

import java.net.URI;
import java.util.Objects;

import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;

public final class RemoteMedia implements RemoteFile {
	
	private final Media media;
	private long size;
	private long estimatedSize;
	
	public RemoteMedia(Media media) {
		this.media = Objects.requireNonNull(media);
		this.size = MediaConstants.UNKNOWN_SIZE;
		this.estimatedSize = 0L;
	}
	
	@Override public Media value() { return media; }
	@Override public URI uri() { return media.uri(); }
	@Override public void size(long size) { this.size = this.estimatedSize = size; }
	@Override public long size() { return size; }
	@Override public void estimatedSize(long size) { this.estimatedSize = size; }
	@Override public long estimatedSize() { return estimatedSize; }
}