package sune.app.mediadown.downloader.smf;

import sune.app.mediadown.download.DownloadInitialState;

// package-private
final class ChunkedDownloadInitialState implements DownloadInitialState {
	
	final int resourceIndex;
	final long srcPosition;
	final long dstPosition;
	
	ChunkedDownloadInitialState(
		int resourceIndex,
		long srcPosition,
		long dstPosition
	) {
		this.resourceIndex = resourceIndex;
		this.srcPosition   = srcPosition;
		this.dstPosition   = dstPosition;
	}
	
	public static final ChunkedDownloadInitialState checkOrDefault(DownloadInitialState state) {
		if(state instanceof ChunkedDownloadInitialState) {
			return (ChunkedDownloadInitialState) state;
		}
		
		return new ChunkedDownloadInitialState(0, 0L, 0L);
	}
}
