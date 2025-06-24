package sune.app.mediadown.downloader.wms;

import sune.app.mediadown.download.DownloadInitialState;

// package-private
final class SegmentedDownloadInitialState implements DownloadInitialState {
	
	final int resourceIndex;
	final int segmentIndex;
	final long srcPosition;
	final long dstPosition;
	
	SegmentedDownloadInitialState(
		int resourceIndex,
		int segmentIndex,
		long srcPosition,
		long dstPosition
	) {
		this.resourceIndex = resourceIndex;
		this.segmentIndex  = segmentIndex;
		this.srcPosition   = srcPosition;
		this.dstPosition   = dstPosition;
	}
	
	public static final SegmentedDownloadInitialState checkOrDefault(DownloadInitialState state) {
		if(state instanceof SegmentedDownloadInitialState) {
			return (SegmentedDownloadInitialState) state;
		}
		
		return new SegmentedDownloadInitialState(0, 0, 0L, 0L);
	}
}
