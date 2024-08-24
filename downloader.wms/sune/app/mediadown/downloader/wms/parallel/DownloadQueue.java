package sune.app.mediadown.downloader.wms.parallel;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import sune.app.mediadown.downloader.wms.common.RemoteFile;
import sune.app.mediadown.downloader.wms.common.RemoteFileSegment;

// package-private
final class DownloadQueue {
	
	/* Implementation note:
	 * Use a read-only list and an atomic index, so that concurrent access
	 * is possible without any synchronization (e.g. as with an iterator).
	 */
	
	private final List<? extends RemoteFile> segments;
	private final AtomicInteger nextIndex = new AtomicInteger();
	
	public DownloadQueue(List<? extends RemoteFile> segments) {
		this.segments = Objects.requireNonNull(segments);
	}
	
	public boolean hasNext() {
		return nextIndex.get() < segments.size();
	}
	
	public RemoteFileSegment next() {
		final int index = nextIndex.getAndIncrement();
		
		// After calling hasNext method there is a possibility that an another
		// thread will update the nextIndex value, therefore we must check the
		// obtained value locally before accessing it.
		if(index >= segments.size()) {
			return null; // Do not throw an exception
		}
		
		return (RemoteFileSegment) segments.get(index);
	}
	
	public long averageSize() {
		return (long) (
			segments.stream()
				.mapToLong(RemoteFile::estimatedSize)
				.average()
				.getAsDouble()
		);
	}
}