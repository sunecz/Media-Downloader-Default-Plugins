package sune.app.mediadown.downloader.wms.common;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import sune.app.mediadown.media.Media;

// TODO: Implement
public final class Segments {
	
	private final Media media;
	private final List<? extends RemoteFile> segments;
	private final Path output;
	private final double duration;
	
	public Segments(
		Media media,
		List<? extends RemoteFile> segments,
		Path output,
		double duration
	) {
		this.media = Objects.requireNonNull(media);
		this.segments = Objects.requireNonNull(segments);
		this.output = Objects.requireNonNull(output);
		this.duration = duration;
	}
	
	public long estimatedSize() {
		return segments.stream().mapToLong(RemoteFile::estimatedSize).sum();
	}
	
	public Media media() { return media; }
	public List<? extends RemoteFile> segments() { return segments; }
	public Path output() { return output; }
	public double duration() { return duration; }
}