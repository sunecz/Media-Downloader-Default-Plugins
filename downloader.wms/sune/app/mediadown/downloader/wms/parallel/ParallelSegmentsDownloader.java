package sune.app.mediadown.downloader.wms.parallel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import sune.app.mediadown.download.Destination;
import sune.app.mediadown.download.DownloadConfiguration;
import sune.app.mediadown.download.InternalDownloader;
import sune.app.mediadown.downloader.wms.common.DownloadRetry;
import sune.app.mediadown.downloader.wms.common.DownloaderState;
import sune.app.mediadown.downloader.wms.common.InternalSegmentsDownloader;
import sune.app.mediadown.downloader.wms.common.RemoteFile;
import sune.app.mediadown.downloader.wms.common.RetryDownloadSimpleTracker;
import sune.app.mediadown.downloader.wms.common.Segments;
import sune.app.mediadown.downloader.wms.common.SegmentsDownloaderBase;
import sune.app.mediadown.downloader.wms.common.SimpleDownloadRetry;
import sune.app.mediadown.event.Event;
import sune.app.mediadown.event.EventRegistry;
import sune.app.mediadown.event.Listener;
import sune.app.mediadown.event.tracker.Tracker;
import sune.app.mediadown.event.tracker.TrackerEvent;
import sune.app.mediadown.event.tracker.TrackerManager;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;

public final class ParallelSegmentsDownloader implements InternalSegmentsDownloader {
	
	// TODO: Implementation note
	
	private final DownloaderState state;
	private final DownloadConfiguration.Builder configurationBuilder;
	private final Supplier<InternalDownloader> downloaderCreator;
	private final EventRegistry<TrackerEvent> eventRegistry;
	
	private final DownloadWorker[] workers;
	private final RetryEntry[] retryEntries;
	private int retryCount = 0;
	private Merger merger;
	
	private Tracker previousTracker;
	private RetryDownloadSimpleTracker retryTracker;
	private DownloadRetry downloadRetry;
	
	public ParallelSegmentsDownloader(
		DownloaderState state,
		DownloadConfiguration.Builder configurationBuilder,
		Supplier<InternalDownloader> downloaderCreator
	) {
		this.state = Objects.requireNonNull(state);
		this.configurationBuilder = Objects.requireNonNull(configurationBuilder);
		this.downloaderCreator = Objects.requireNonNull(downloaderCreator);
		this.eventRegistry = new EventRegistry<>();
		this.workers = new DownloadWorker[state.numOfWorkers()];
		this.retryEntries = new RetryEntry[state.numOfWorkers()];
	}
	
	private static final Path temporaryFile(Path output, int i) {
		String fileName = Utils.OfPath.fileName(output);
		Path tempFile = output.getParent().resolve(String.format("%s.p%d.part", fileName, i));
		Ignore.callVoid(() -> NIO.deleteFile(tempFile));
		return tempFile;
	}
	
	private final boolean download(
		List<? extends RemoteFile> segments,
		Destination destination
	) throws Exception {
		final int numOfWorkers = workers.length;
		Path output = destination.path();
		DownloadQueue queue = new DownloadQueue(segments);
		Merger merger = new Merger(output);
		Synchronizer synchronizer = new Synchronizer(numOfWorkers);
		this.merger = merger;
		
		for(int i = 0; i < numOfWorkers; ++i) {
			Path tempPath = temporaryFile(output, i);
			SerialDownloader serialDownloader = new SerialDownloaderImpl(
				i, state, configurationBuilder, downloaderCreator.get()
			);
			
			workers[i] = new DownloadWorker(
				synchronizer,
				queue,
				merger,
				serialDownloader,
				tempPath
			);
		}
		
		for(DownloadWorker worker : workers) {
			worker.start();
		}
		
		merger.start();
		
		try {
			synchronizer.await();
			// Notify the merger that it should terminate after no more requests are available,
			// otherwise we would wait here forever.
			merger.terminate();
			merger.await();
			return true;
		} finally {
			for(DownloadWorker worker : workers) {
				worker.stop();
			}
			
			merger.stop();
			
			// Clean up temporary files
			for(DownloadWorker worker : workers) {
				try {
					NIO.deleteFile(worker.output());
				} catch(IOException ex) {
					// Ignore
				}
			}
			
			// Clean up
			Arrays.fill(workers, null);
			Arrays.fill(retryEntries, null);
			this.merger = null;
			previousTracker = null;
		}
	}
	
	private final void showRetryText(RetryEntry entry) {
		final TrackerManager trackerManager = state.trackerManager();
		
		if(retryTracker == null) {
			retryTracker = new RetryDownloadSimpleTracker(state.maxRetryAttempts());
			eventRegistry.bind(retryTracker, TrackerEvent.UPDATE);
		}
		
		if(previousTracker == null) {
			previousTracker = trackerManager.tracker();
			trackerManager.tracker(retryTracker);
		}
	}
	
	private final void hideRetryText() {
		if(previousTracker == null) {
			return;
		}
		
		final TrackerManager trackerManager = state.trackerManager();
		trackerManager.tracker(previousTracker);
		previousTracker = null;
	}
	
	private final boolean retry(int id, int attempt) {
		if(downloadRetry == null) {
			downloadRetry = new SimpleDownloadRetry(state);
		}
		
		long waitTime = downloadRetry.waitTime(attempt);
		retryEnter(id, waitTime);
		
		try {
			return downloadRetry.waitForRetry(attempt, waitTime, retryTracker);
		} finally {
			retryExit(id);
		}
	}
	
	private final void retryEnter(int id, long waitTime) {
		RetryEntry minEntry = null;
		
		synchronized(retryEntries) {
			long now = System.nanoTime();
			retryEntries[id] = new RetryEntry(waitTime, now);
			
			if(++retryCount == workers.length) {
				long minRem = Long.MAX_VALUE;
				
				for(RetryEntry entry : retryEntries) {
					long rem = entry.waitTime - (now - entry.entryTime);
					
					if(rem < minRem) {
						minRem = rem;
						minEntry = entry;
					}
				}
			}
		}
		
		if(minEntry != null) {
			showRetryText(minEntry);
		}
	}
	
	private final void retryExit(int id) {
		synchronized(retryEntries) {
			retryEntries[id] = null;
			--retryCount;
		}
		
		hideRetryText();
	}
	
	@Override
	public boolean download(Segments segments) throws Exception {
		return download(segments.segments(), segments.destination());
	}
	
	@Override
	public void close() throws Exception {
		for(DownloadWorker worker : workers) {
			if(worker == null) {
				continue;
			}
			
			try {
				worker.close();
			} catch(Exception ex) {
				// Ignore
			}
		}
		
		Merger merger;
		if((merger = this.merger) != null) {
			merger.close();
		}
	}
	
	@Override
	public <V> void addEventListener(
		Event<? extends TrackerEvent, V> event,
		Listener<V> listener
	) {
		eventRegistry.add(event, listener);
	}
	
	@Override
	public <V> void removeEventListener(
		Event<? extends TrackerEvent, V> event,
		Listener<V> listener
	) {
		eventRegistry.remove(event, listener);
	}
	
	private final class SerialDownloaderImpl
			extends SegmentsDownloaderBase
			implements SerialDownloader {
		
		private final int id;
		
		public SerialDownloaderImpl(
			int id,
			DownloaderState state,
			DownloadConfiguration.Builder configurationBuilder,
			InternalDownloader downloader
		) {
			super(state, configurationBuilder, downloader);
			this.id = id;
		}
		
		@Override
		protected final boolean doRetry(int attempt) {
			return retry(id, attempt);
		}
		
		@Override
		public boolean downloadSegment(
			RemoteFile segment,
			Destination destination
		) throws Exception {
			return super.downloadSegment(segment, destination); // Delegate
		}
		
		@Override public void addWritten(long value) { written += value; }
		@Override public long written() { return written; }
	}
	
	private static final class RetryEntry {
		
		final long waitTime;
		final long entryTime;
		
		public RetryEntry(long waitTime, long entryTime) {
			this.waitTime = waitTime;
			this.entryTime = entryTime;
		}
	}
}