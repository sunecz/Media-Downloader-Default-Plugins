package sune.app.mediadown.downloader.wms.common;

import java.util.List;
import java.util.Objects;

import sune.app.mediadown.download.Destination;
import sune.app.mediadown.media.Media;

public final class Segments {
	
	private final Media media;
	private final List<? extends RemoteFile> segments;
	private final Destination destination;
	private final double duration;
	
	public Segments(
		Media media,
		List<? extends RemoteFile> segments,
		Destination destination,
		double duration
	) {
		this.media = Objects.requireNonNull(media);
		this.segments = Objects.requireNonNull(segments);
		this.destination = Objects.requireNonNull(destination);
		this.duration = duration;
	}
	
	public long estimatedSize() {
		return segments.stream().mapToLong(RemoteFile::estimatedSize).sum();
	}
	
	public Media media() { return media; }
	public List<? extends RemoteFile> segments() { return segments; }
	public Destination destination() { return destination; }
	public double duration() { return duration; }
}