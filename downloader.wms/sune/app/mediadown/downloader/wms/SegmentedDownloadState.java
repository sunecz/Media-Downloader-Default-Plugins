package sune.app.mediadown.downloader.wms;

import java.util.Set;

import sune.app.mediadown.download.DownloadState;
import sune.app.mediadown.pipeline.state.Metrics;
import sune.app.mediadown.pipeline.state.MetricsComparator;

// package-private
final class SegmentedDownloadState implements DownloadState {
	
	private final DownloadMetrics metrics = new DownloadMetrics();
	
	public void beginResource(
		int resourceIndex,
		int segmentIndex,
		long srcPosition,
		long dstPosition
	) {
		metrics.beginResource(resourceIndex, segmentIndex, srcPosition, dstPosition);
	}
	
	public void beginSegment(
		int segmentIndex,
		long srcPosition,
		long dstPosition
	) {
		metrics.beginSegment(segmentIndex, srcPosition, dstPosition);
	}
	
	public static final MetricsComparator metricsComparator() {
		return new DownloadMetricsComparator();
	}
	
	@Override
	public Metrics metrics() {
		return Metrics.freeze(metrics);
	}
	
	private static final class DownloadMetrics implements Metrics {
		
		private static final String TYPE = "download:segmented";
		private static final Set<String> NAMES = Set.of(
			"resource_index",
			"segment_index",
			"src_position",
			"dst_position"
		);
		
		private volatile int  resourceIndex;
		private volatile int  segmentIndex;
		private volatile long srcPosition;
		private volatile long dstPosition;
		
		public void beginResource(
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
		
		public void beginSegment(
			int segmentIndex,
			long srcPosition,
			long dstPosition
		) {
			this.segmentIndex = segmentIndex;
			this.srcPosition  = srcPosition;
			this.dstPosition  = dstPosition;
		}
		
		@Override
		public String type() {
			return TYPE;
		}
		
		@Override
		public Object get(String name) {
			switch(name) {
				case "resource_index": return resourceIndex;
				case "segment_index":  return segmentIndex;
				case "src_position":   return srcPosition;
				case "dst_position":   return dstPosition;
				default:               return null;
			}
		}
		
		@Override
		public Set<String> names() {
			return NAMES;
		}
	}
	
	private static final class DownloadMetricsComparator implements MetricsComparator {
		
		@Override
		public boolean compare(Metrics a, Metrics b) {
			// We can assume that both metrics are non-null and of the same type
			if(!DownloadMetrics.TYPE.equals(a.type())) {
				return false;
			}
			
			return (
				((int)  a.get("segment_index")  != (int)  b.get("segment_index")) ||
				((long) a.get("src_position")   != (long) b.get("src_position")) ||
				((long) a.get("dst_position")   != (long) b.get("dst_position")) ||
				((int)  a.get("resource_index") != (int)  b.get("resource_index"))
			);
		}
	}
}
