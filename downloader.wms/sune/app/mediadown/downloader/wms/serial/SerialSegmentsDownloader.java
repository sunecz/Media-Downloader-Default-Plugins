package sune.app.mediadown.downloader.wms.serial;

import java.util.Objects;
import java.util.function.Supplier;

import sune.app.mediadown.download.DownloadConfiguration;
import sune.app.mediadown.download.InternalDownloader;
import sune.app.mediadown.downloader.wms.common.DownloadRetry;
import sune.app.mediadown.downloader.wms.common.DownloaderState;
import sune.app.mediadown.downloader.wms.common.RetryDownloadSimpleTracker;
import sune.app.mediadown.downloader.wms.common.SegmentsDownloaderBase;
import sune.app.mediadown.downloader.wms.common.SimpleDownloadRetry;
import sune.app.mediadown.event.tracker.Tracker;
import sune.app.mediadown.event.tracker.TrackerEvent;
import sune.app.mediadown.event.tracker.TrackerManager;

public final class SerialSegmentsDownloader extends SegmentsDownloaderBase {
	
	private Tracker previousTracker;
	private RetryDownloadSimpleTracker retryTracker;
	private DownloadRetry downloadRetry;
	
	public SerialSegmentsDownloader(
		DownloaderState state,
		DownloadConfiguration.Builder configurationBuilder,
		Supplier<InternalDownloader> downloaderCreator
	) {
		super(state, configurationBuilder, Objects.requireNonNull(downloaderCreator).get());
	}
	
	@Override
	protected boolean doRetry(int attempt) {
		final TrackerManager trackerManager = state.trackerManager();
		
		if(retryTracker == null) {
			retryTracker = new RetryDownloadSimpleTracker(state.maxRetryAttempts());
			eventRegistry.bind(retryTracker, TrackerEvent.UPDATE);
		}
		
		if(previousTracker == null) {
			previousTracker = trackerManager.tracker();
			trackerManager.tracker(retryTracker);
		}
		
		try {
			if(downloadRetry == null) {
				downloadRetry = new SimpleDownloadRetry(state);
			}
			
			return downloadRetry.waitForRetry(attempt, retryTracker);
		} finally {
			trackerManager.tracker(previousTracker);
			previousTracker = null;
		}
	}
}