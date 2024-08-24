package sune.app.mediadown.downloader.wms.parallel;

import java.util.Objects;

// package-private
final class MergeRequest implements Comparable<MergeRequest> {
	
	final DownloadWorker worker;
	final int index;
	final long offset;
	final long size;
	final int epoch;
	
	public MergeRequest(
		DownloadWorker worker,
		int index,
		long offset,
		long size,
		int epoch
	) {
		this.worker = Objects.requireNonNull(worker);
		this.index = index;
		this.offset = offset;
		this.size = size;
		this.epoch = epoch;
	}
	
	@Override
	public int compareTo(MergeRequest other) {
		return Integer.compare(index, other.index);
	}
}