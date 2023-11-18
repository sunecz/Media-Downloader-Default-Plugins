package sune.app.mediadown.downloader.wms;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import sune.app.mediadown.InternalState;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.TaskStates;
import sune.app.mediadown.concurrent.SyncObject;
import sune.app.mediadown.concurrent.Worker;
import sune.app.mediadown.conversion.ConversionMedia;
import sune.app.mediadown.download.Download;
import sune.app.mediadown.download.DownloadConfiguration;
import sune.app.mediadown.download.DownloadContext;
import sune.app.mediadown.download.DownloadResult;
import sune.app.mediadown.download.FileDownloader;
import sune.app.mediadown.download.InternalDownloader;
import sune.app.mediadown.download.MediaDownloadConfiguration;
import sune.app.mediadown.download.segment.FileSegment;
import sune.app.mediadown.download.segment.FileSegmentsHolder;
import sune.app.mediadown.event.DownloadEvent;
import sune.app.mediadown.event.Event;
import sune.app.mediadown.event.EventRegistry;
import sune.app.mediadown.event.Listener;
import sune.app.mediadown.event.tracker.DownloadTracker;
import sune.app.mediadown.event.tracker.PipelineProgress;
import sune.app.mediadown.event.tracker.PipelineStates;
import sune.app.mediadown.event.tracker.PlainTextTracker;
import sune.app.mediadown.event.tracker.SimpleTracker;
import sune.app.mediadown.event.tracker.Tracker;
import sune.app.mediadown.event.tracker.TrackerManager;
import sune.app.mediadown.event.tracker.WaitTracker;
import sune.app.mediadown.exception.RejectedResponseException;
import sune.app.mediadown.gui.table.ResolvedMedia;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SegmentedMedia;
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.pipeline.DownloadPipelineResult;
import sune.app.mediadown.util.Metadata;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Opt.OptCondition;
import sune.app.mediadown.util.Pair;
import sune.app.mediadown.util.Range;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;

public final class SegmentsDownloader implements Download, DownloadResult {
	
	private static final HttpHeaders HEADERS = Web.Headers.ofSingle("Accept", "*/*");
	private static final long TIME_UPDATE_RESOLUTION_MS = 50L;
	
	private final Translation translation = MediaDownloader.translation().getTranslation("plugin.downloader.wms");
	private final TrackerManager trackerManager = new TrackerManager();
	private final EventRegistry<DownloadEvent> eventRegistry = new EventRegistry<>();
	
	private final Media media;
	private final Path dest;
	private final MediaDownloadConfiguration configuration;
	private final int maxRetryAttempts;
	private final boolean asyncTotalSize;
	private final int waitOnRetryMs;
	
	private final InternalState state = new InternalState();
	private final SyncObject lockPause = new SyncObject();
	
	private TotalSizeComputer totalSizeComputer;
	private final AtomicLong size = new AtomicLong(MediaConstants.UNKNOWN_SIZE);
	private DownloadPipelineResult pipelineResult;
	private InternalDownloader downloader;
	private DownloadTracker downloadTracker;
	private Exception exception;
	private Tracker previousTracker;
	private RetryDownloadSimpleTracker retryTracker;
	private DownloadConfiguration.Builder configurationBuilder;
	private DownloadEventHandler handler;
	
	SegmentsDownloader(Media media, Path dest, MediaDownloadConfiguration configuration, int maxRetryAttempts,
			boolean asyncTotalSize, int waitOnRetryMs) {
		this.media            = Objects.requireNonNull(media);
		this.dest             = Objects.requireNonNull(dest);
		this.configuration    = Objects.requireNonNull(configuration);
		this.maxRetryAttempts = checkMaxRetryAttempts(maxRetryAttempts);
		this.asyncTotalSize   = asyncTotalSize;
		this.waitOnRetryMs    = checkMilliseconds(waitOnRetryMs);
		trackerManager.tracker(new WaitTracker());
	}
	
	private static final int checkMaxRetryAttempts(int maxRetryAttempts) {
		if(maxRetryAttempts < 0) {
			throw new IllegalArgumentException();
		}
		
		return maxRetryAttempts;
	}
	
	private static final int checkMilliseconds(int ms) {
		if(ms < 0) {
			throw new IllegalArgumentException();
		}
		
		return ms;
	}
	
	private static final int compareFirstLongestString(String a, String b) {
		int cmp; return (cmp = Integer.compare(b.length(), a.length())) == 0 ? 1 : cmp;
	}
	
	private static final long sizeOf(URI uri, HttpHeaders headers) throws Exception {
		return Web.size(Request.of(uri).headers(headers).HEAD());
	}
	
	private static final Pair<Boolean, Long> sizeOrEstimatedSize(
			List<? extends RemoteFile> segments,
			List<? extends RemoteFile> subtitles
	) {
		boolean allHaveSize = stream(segments, subtitles).allMatch((s) -> s.size() > 0L);
		ToLongFunction<? super RemoteFile> mapper = allHaveSize ? RemoteFile::size : RemoteFile::estimatedSize;
		return new Pair<>(allHaveSize, stream(segments, subtitles).mapToLong(mapper).sum());
	}
	
	private static final int numberOfAsyncTotalSizeComputeWorkers() {
		return Math.max(1, MediaDownloader.configuration().acceleratedDownload() - 1);
	}
	
	private static final <P, A> void sync(P value, BiConsumer<P, A> function, A arg) {
		synchronized(value) {
			function.accept(value, arg);
		}
	}
	
	private static final <P, R> R sync(P value, Function<P, R> function) {
		synchronized(value) {
			return function.apply(value);
		}
	}
	
	private static final OptCondition<Media> conditionIsSegmentedAndNotSubtitles() {
		return OptCondition.ofAll(
			Media::isSegmented,
			(m) -> !m.type().is(MediaType.SUBTITLES)
		);
	}
	
	private static final List<Media> segmentedMedia(Media media) {
		return MediaUtils.filterRecursive(media, conditionIsSegmentedAndNotSubtitles());
	}
	
	private static final FileSegmentsHolder<?> mediaToSegmentsHolder(Media media) {
		return ((SegmentedMedia) media).segments().get(0);
	}
	
	private static final <T> Stream<T> stream(List<? extends T> a, List<? extends T> b) {
		return Stream.concat(a.stream(), b.stream());
	}
	
	private static final <T> Stream<T> reversedStream(List<? extends T> list) {
		final int from = 0, to = list.size(); // Implicit null-check
		return IntStream.range(from, to).map((i) -> to - i + from - 1).mapToObj(list::get);
	}
	
	private static final InternalDownloader createDownloader(TrackerManager manager) {
		return new FileDownloader(manager);
	}
	
	private static final boolean allowOnlySuccessfulResponse(Response response) {
		return response.statusCode() == 200;
	}
	
	private final void waitMs(long ms, TimeUpdateTrackerBase tracker) {
		if(ms <= 0L) {
			return;
		}
		
		// Optimization for low wait values
		if(ms <= TIME_UPDATE_RESOLUTION_MS) {
			try {
				Thread.sleep(ms);
			} catch(InterruptedException ex) {
				// Ignore
			}
			
			return; // No need to continue
		}
		
		// Update the tracker, so it is visible to the user
		tracker.totalTimeMs(ms);
		
		for(long first  = System.nanoTime(),
				 target = ms * 1000000L,
				 time;
				(time = System.nanoTime() - first) < target;) {
			// Make the loop pausable and stoppable
			if(!checkState()) break;
			
			// Update the tracker, so it is visible to the user
			tracker.timeMs(time / 1000000L);
			
			try {
				Thread.sleep(TIME_UPDATE_RESOLUTION_MS);
			} catch(InterruptedException ex) {
				break;
			}
		}
	}
	
	private final boolean checkState() {
		// Wait for resume, if paused
		if(isPaused()) {
			lockPause.await();
		}
		
		// If already not running, do not continue
		return state.is(TaskStates.RUNNING);
	}
	
	private final void doConversion(ResolvedMedia output, double duration, List<ConversionMedia> inputs) {
		pipelineResult = DownloadPipelineResult.doConversion(output, inputs, Metadata.of("duration", duration));
	}
	
	private final boolean computeTotalSize(List<? extends RemoteFile> segments, List<? extends RemoteFile> subtitles)
			throws Exception {
		if(!MediaDownloader.configuration().computeStreamSize()) {
			// User requested no stream size computation, at least estimate the total size
			Pair<Boolean, Long> sizeResult = sizeOrEstimatedSize(segments, subtitles);
			sizeSet(sizeResult.b);
			// Total size is not final
			return false;
		}
		
		// Obtain the total size compute strategy and compute the size
		totalSizeComputer = asyncTotalSize ? new AsynchronousTotalSizeComputer() : new SynchronousTotalSizeComputer();
		return totalSizeComputer.compute(segments, subtitles);
	}
	
	private final void sizeSet(long value) {
		size.set(value);
		
		if(downloadTracker != null) {
			downloadTracker.updateTotal(value);
		}
	}
	
	private final void sizeAdd(long value) {
		long current = size.addAndGet(value);
		
		if(downloadTracker != null) {
			downloadTracker.updateTotal(current);
		}
	}
	
	private final List<Path> temporaryFiles(int count) {
		List<Path> tempFiles = new ArrayList<>(count);
		String fileName = Utils.OfPath.fileName(dest);
		
		for(int i = 0; i < count; ++i) {
			Path tempFile = dest.getParent().resolve(fileName + "." + i + ".part");
			Ignore.callVoid(() -> NIO.deleteFile(tempFile));
			tempFiles.add(tempFile);
		}
		
		return tempFiles;
	}
	
	private final InternalDownloader ensureInternalDownloader() {
		if(downloader == null) {
			downloader = createDownloader(new TrackerManager());
			downloader.setTracker(new DownloadTracker());
		}
		
		return downloader;
	}
	
	private final boolean doDownload(FileSegmentsHolder<?> segmentsHolder, Path output) throws Exception {
		long bytes = 0L;
		
		for(FileSegment segment : segmentsHolder.segments()) {
			if(!checkState()) return false;
			
			Request request = Request.of(segment.uri()).headers(HEADERS).GET();
			boolean lastAttempt = false;
			boolean error = false;
			Exception exception = null;
			long downloadedBytes = 0L;
			
			for(int i = 0; (error || downloadedBytes <= 0L) && i <= maxRetryAttempts; ++i) {
				if(!checkState()) return false;
				
				lastAttempt = i == maxRetryAttempts;
				handler.setPropagateError(lastAttempt);
				exception = null;
				error = false;
				
				// Only display the text, if we're actually retrying
				if(i > 0) {
					if(retryTracker == null) {
						retryTracker = new RetryDownloadSimpleTracker();
					}
					
					if(previousTracker == null) {
						previousTracker = trackerManager.tracker();
						trackerManager.tracker(retryTracker);
					}
					
					retryTracker.attempt(i);
					int waitMs = (int) (waitOnRetryMs * Math.pow(i, 4.0 / 3.0));
					waitMs(waitMs, retryTracker);
					retryTracker.timeMs(-1L);
					
					if(!checkState()) return false;
				}
				
				try {
					Range<Long> rangeOutput = new Range<>(bytes, -1L);
					DownloadConfiguration downloadConfiguration
						= configurationBuilder.rangeOutput(rangeOutput).totalBytes(segment.size()).build();
					downloadedBytes = downloader.start(request, output, downloadConfiguration);
					error = downloader.isError() || downloadedBytes <= 0L;
				} catch(InterruptedException ex) {
					// When stopped, immediately break from the loop
					return false;
				} catch(RejectedResponseException ex) {
					// Retry, if the response is rejected by the filter
					error = true;
				} catch(Exception ex) {
					error = true;
					exception = ex;
				}
				
				if(!error) {
					long size = segment.size();
					
					// Check whether the size is already available. If not, get it from
					// the downloader itself. This should fix an issue when just a part
					// of the whole segment was downloaded and the size couldn't be checked,
					// thus the file became incomplete/corrupted.
					if(size < 0L) {
						size = downloader.totalBytes();
					}
					
					// Check whether the downloaded size equals the total size, if not
					// just retry the download again.
					if(size >= 0L && downloadedBytes != size) {
						error = true;
					}
				}
				
				if(error) {
					if(downloadedBytes > 0L) {
						downloadTracker.update(-downloadedBytes);
					}
					
					downloadedBytes = -1L;
				}
			}
			
			if(previousTracker != null) {
				trackerManager.tracker(previousTracker);
				previousTracker = null;
			}
			
			// If even the last attempt failed, throw an exception since there is nothing we can do.
			if(lastAttempt && downloadedBytes <= 0L) {
				throw new IllegalStateException("The last attempt failed");
			}
			
			if(exception != null) {
				throw exception; // Forward the exception
			}
			
			if(downloadedBytes > 0L) {
				bytes += downloadedBytes;
			}
		}
		
		// Allow error propagating since there will be no more retry attempts
		handler.setPropagateError(true);
		return true;
	}
	
	private final boolean doDownload(Request request, Path output) throws Exception {
		downloader.start(request, output, DownloadConfiguration.ofDefault());
		return true;
	}
	
	private final boolean doDownload(Media media, Path output) throws Exception {
		return media.isSegmented()
			? doDownload(((SegmentedMedia) media).segments().get(0), output)
			: doDownload(Request.of(media.uri()).headers(HEADERS).GET(), output);
	}
	
	private final Path subtitlesPath(SubtitlesMedia media, String fileName, Path directory) {
		String extension = Opt.of(media.format().fileExtensions())
			.ifFalse(List::isEmpty).map((l) -> l.get(0))
			.orElseGet(() -> Utils.OfPath.info(media.uri().toString()).extension());
		String language = media.language().codes().stream()
			.sorted(SegmentsDownloader::compareFirstLongestString)
			.findFirst().orElse(null);
		
		return directory.resolve(Utils.OfString.concat(
			".", Utils.OfString::nonEmpty, fileName, language, extension
		));
	}
	
	private final boolean download(List<FileSegmentsHolder<?>> segmentsHolders, List<Path> outputs) throws Exception {
		Iterator<Path> output = outputs.iterator();
		
		for(FileSegmentsHolder<?> segmentsHolder : segmentsHolders) {
			if(!doDownload(segmentsHolder, output.next())) {
				return false;
			}
		}
		
		return true;
	}
	
	private final boolean downloadSubtitles(List<RemoteMedia> subtitles, Path destination) throws Exception {
		if(subtitles.isEmpty()) {
			return true;
		}
		
		Path directory = destination.getParent();
		String baseName = Utils.OfPath.baseName(destination);
		
		for(RemoteMedia subtitle : subtitles) {
			if(!checkState()) return false;
			
			SubtitlesMedia media = (SubtitlesMedia) subtitle.media;
			Path path = subtitlesPath(media, baseName, directory);
			
			if(!doDownload(media, path)) {
				return false;
			}
		}
		
		return true;
	}
	
	@Override
	public void start() throws Exception {
		if(state.is(TaskStates.STARTED) && state.is(TaskStates.RUNNING)) {
			return; // Nothing to do
		}
		
		state.set(TaskStates.RUNNING);
		state.set(TaskStates.STARTED);
		
		eventRegistry.call(DownloadEvent.BEGIN, this);
		
		ensureInternalDownloader();
		Ignore.callVoid(() -> NIO.createFile(dest));
		
		// Prepare the segments that should be downloaded
		List<Media> mediaSingles = segmentedMedia(media);
		List<FileSegmentsHolder<?>> segmentsHolders = mediaSingles.stream()
			.map(SegmentsDownloader::mediaToSegmentsHolder)
			.collect(Collectors.toList());
		
		List<RemoteFile> segments = mediaSingles.stream().flatMap((m) -> {
			double estimatedSize = MediaUtils.estimateTotalSize(m);
			@SuppressWarnings("unchecked")
			List<FileSegment> fileSegments = (List<FileSegment>) mediaToSegmentsHolder(m).segments();
			long est = (long) Math.ceil(estimatedSize / fileSegments.size());
			return fileSegments.stream().map(RemoteFileSegment::new).peek((s) -> s.estimatedSize(est));
		}).collect(Collectors.toList());
		
		// Prepare the subtitles that should be downloaded, may be none
		List<RemoteMedia> subtitles = configuration.selectedMedia(MediaType.SUBTITLES).stream()
			.map(RemoteMedia::new)
			.collect(Collectors.toList());
		
		computeTotalSize(segments, subtitles);
		if(!checkState()) return;
		
		downloadTracker = new DownloadTracker(size.get());
		trackerManager.tracker(downloadTracker);
		
		try {
			List<Path> tempFiles = temporaryFiles(segmentsHolders.size());
			
			handler = new DownloadEventHandler(downloadTracker);
			downloader.addEventListener(DownloadEvent.UPDATE, handler::onUpdate);
			downloader.addEventListener(DownloadEvent.ERROR, handler::onError);
			
			previousTracker = null;
			retryTracker = null;
			
			configurationBuilder = DownloadConfiguration.builder()
				.rangeRequest(new Range<>(0L, -1L))
				.responseFilter(SegmentsDownloader::allowOnlySuccessfulResponse);
			
			download(segmentsHolders, tempFiles);
			downloadSubtitles(subtitles, dest);
			
			if(!checkState()) return;
			// Compute the duration of all segments files and select the maximum
			double duration = segmentsHolders.stream()
				.mapToDouble(FileSegmentsHolder::duration)
				.max().orElse(Double.NaN);
			// Convert the segments files into the final file
			ResolvedMedia output = new ResolvedMedia(media, dest, configuration);
			List<ConversionMedia> inputs = Utils.zip(mediaSingles.stream(), tempFiles.stream(), Pair::new)
				.map((p) -> new ConversionMedia(p.a, p.b, Double.NaN))
				.collect(Collectors.toList());
			doConversion(output, duration, inputs);
			
			state.set(TaskStates.DONE);
		} catch(Exception ex) {
			exception = ex;
			state.set(TaskStates.ERROR);
			eventRegistry.call(DownloadEvent.ERROR, this);
			throw ex; // Forward the exception
		} finally {
			stop();
		}
	}
	
	@Override
	public void stop() throws Exception {
		if(state.is(TaskStates.STOPPED))
			return; // Nothing to do
		
		state.unset(TaskStates.RUNNING);
		state.unset(TaskStates.PAUSED);
		lockPause.unlock();
		
		if(downloader != null) {
			downloader.stop();
		}
		
		if(totalSizeComputer != null) {
			totalSizeComputer.stop();
		}
		
		if(!state.is(TaskStates.DONE)) {
			state.set(TaskStates.STOPPED);
		}
		
		eventRegistry.call(DownloadEvent.END, this);
	}
	
	@Override
	public void pause() throws Exception {
		if(state.is(TaskStates.PAUSED))
			return; // Nothing to do
		
		if(downloader != null) {
			downloader.pause();
		}
		
		if(totalSizeComputer != null) {
			totalSizeComputer.pause();
		}
		
		state.unset(TaskStates.RUNNING);
		state.set(TaskStates.PAUSED);
		eventRegistry.call(DownloadEvent.PAUSE, this);
	}
	
	@Override
	public void resume() throws Exception {
		if(!state.is(TaskStates.PAUSED))
			return; // Nothing to do
		
		if(downloader != null) {
			downloader.resume();
		}
		
		if(totalSizeComputer != null) {
			totalSizeComputer.resume();
		}
		
		state.unset(TaskStates.PAUSED);
		state.set(TaskStates.RUNNING);
		lockPause.unlock();
		eventRegistry.call(DownloadEvent.RESUME, this);
	}
	
	@Override
	public Download download() {
		return this;
	}
	
	@Override
	public DownloadPipelineResult pipelineResult() {
		return pipelineResult;
	}
	
	@Override
	public boolean isRunning() {
		return state.is(TaskStates.RUNNING);
	}
	
	@Override
	public boolean isStarted() {
		return state.is(TaskStates.STARTED);
	}
	
	@Override
	public boolean isDone() {
		return state.is(TaskStates.DONE);
	}
	
	@Override
	public boolean isPaused() {
		return state.is(TaskStates.PAUSED);
	}
	
	@Override
	public boolean isStopped() {
		return state.is(TaskStates.STOPPED);
	}
	
	@Override
	public boolean isError() {
		return state.is(TaskStates.ERROR);
	}
	
	@Override
	public <V> void addEventListener(Event<? extends DownloadEvent, V> event, Listener<V> listener) {
		eventRegistry.add(event, listener);
	}
	
	@Override
	public <V> void removeEventListener(Event<? extends DownloadEvent, V> event, Listener<V> listener) {
		eventRegistry.remove(event, listener);
	}
	
	@Override
	public TrackerManager trackerManager() {
		return trackerManager;
	}
	
	@Override
	public Exception exception() {
		return exception;
	}
	
	@Override
	public Request request() {
		// There are many requests, just return null
		return null;
	}
	
	@Override
	public Path output() {
		return dest;
	}
	
	@Override
	public DownloadConfiguration configuration() {
		// There are many download configurations, just return null
		return null;
	}
	
	@Override
	public Response response() {
		// There are many responses, just return null
		return null;
	}
	
	@Override
	public long totalBytes() {
		return size.get();
	}
	
	private final class DownloadEventHandler {
		
		private final DownloadTracker tracker;
		private final AtomicLong lastSize = new AtomicLong();
		private boolean propagateError = true;
		
		public DownloadEventHandler(DownloadTracker tracker) {
			this.tracker = Objects.requireNonNull(tracker);
		}
		
		public void onUpdate(DownloadContext context) {
			DownloadTracker downloadTracker = (DownloadTracker) context.trackerManager().tracker();
			long current = downloadTracker.current();
			long delta = current - lastSize.get();
			
			tracker.update(delta);
			lastSize.set(current);
			
			eventRegistry.call(DownloadEvent.UPDATE, SegmentsDownloader.this);
		}
		
		public void onError(DownloadContext context) {
			if(!propagateError)
				return; // Ignore the error, if needed (used for retries)
			
			exception = context.exception();
			state.set(TaskStates.ERROR);
			eventRegistry.call(DownloadEvent.ERROR, SegmentsDownloader.this);
		}
		
		public void setPropagateError(boolean value) {
			propagateError = value;
		}
	}
	
	private final class RetryDownloadSimpleTracker extends SimpleTracker implements TimeUpdateTrackerBase {
		
		private String progressText;
		private int attempt;
		private long timeMs = -1L;
		private long totalTimeMs;
		
		private final void updateText() {
			String progressText;
			
			if(timeMs >= 0L) {
				String format = translation.getSingle("progress.retry_attempt_wait");
				progressText = Utils.format(format, "attempt", attempt, "total_attempts", maxRetryAttempts,
					"time", Utils.OfFormat.time(timeMs, TimeUnit.MILLISECONDS, true),
					"total_time", Utils.OfFormat.time(totalTimeMs, TimeUnit.MILLISECONDS, true));
			} else {
				String format = translation.getSingle("progress.retry_attempt");
				progressText = Utils.format(format, "attempt", attempt, "total_attempts", maxRetryAttempts);
			}
			
			this.progressText = progressText;
			update();
		}
		
		public void attempt(int attempt) {
			this.attempt = attempt;
			updateText();
		}
		
		@Override
		public void timeMs(long timeMs) {
			this.timeMs = timeMs;
			updateText();
		}
		
		@Override
		public void totalTimeMs(long totalTimeMs) {
			this.totalTimeMs = totalTimeMs;
			updateText();
		}
		
		@Override
		public String state() {
			return PipelineStates.RETRY;
		}
		
		@Override
		public double progress() {
			return PipelineProgress.PROCESSING;
		}
		
		@Override
		public String textProgress() {
			return progressText;
		}
	}
	
	private final class SynchronousTotalSizeComputer implements TotalSizeComputer {
		
		private Worker worker;
		
		@Override
		public boolean compute(
				List<? extends RemoteFile> segments,
				List<? extends RemoteFile> subtitles
		) throws Exception {
			Pair<Boolean, Long> sizeResult = sizeOrEstimatedSize(segments, subtitles);
			sizeSet(sizeResult.b);
			
			// All files (segments and subtitles) have computed size (not estimated)
			if(sizeResult.a) return true;
			
			String text = translation.getSingle("progress.compute_total_size");
			PlainTextTracker tracker = new PlainTextTracker();
			trackerManager.tracker(tracker);
			tracker.text(text);
			tracker.progress(0.0);
			
			double count = segments.size() + subtitles.size();
			AtomicInteger counter = new AtomicInteger();
			worker = Worker.createThreadedWorker();
			try {
				for(RemoteFile file : Utils.iterable(Stream.concat(segments.stream(), subtitles.stream()).iterator())) {
					if(!checkState()) {
						// Important to interrupt before break
						worker.stop();
						// Exit the loop
						break;
					}
					
					long segmentSize = file.size();
					if(segmentSize > 0L) {
						sizeAdd(segmentSize - file.estimatedSize());
						tracker.progress(counter.incrementAndGet() / count);
					} else {
						worker.submit(() -> {
							long fileSize = MediaConstants.UNKNOWN_SIZE;
							
							for(int i = 0; fileSize < 0L && i <= maxRetryAttempts; ++i) {
								fileSize = Ignore.defaultValue(() -> sizeOf(file.uri(), HEADERS),
								                               MediaConstants.UNKNOWN_SIZE);
							}
							
							file.size(fileSize);
							if(fileSize > 0L) {
								sizeAdd(fileSize - file.estimatedSize());
							}
							
							tracker.progress(counter.incrementAndGet() / count);
						});
					}
				}
				
				worker.waitTillDone();
				return true;
			} finally {
				worker.stop();
				worker = null;
			}
		}
		
		@Override
		public void stop() throws Exception {
			if(worker != null) {
				worker.stop();
			}
		}
		
		@Override
		public void pause() throws Exception {
			if(worker != null) {
				worker.pause();
			}
		}
		
		@Override
		public void resume() throws Exception {
			if(worker != null) {
				worker.resume();
			}
		}
	}
	
	private final class AsynchronousTotalSizeComputer implements TotalSizeComputer {
		
		private Worker workerOuter;
		private Worker workerInner;
		
		@Override
		public boolean compute(
				List<? extends RemoteFile> segments,
				List<? extends RemoteFile> subtitles
		) throws Exception {
			Pair<Boolean, Long> sizeResult = sizeOrEstimatedSize(segments, subtitles);
			sizeSet(sizeResult.b);
			
			// All files (segments and subtitles) have computed size (not estimated)
			if(sizeResult.a) return true;
			
			workerOuter = Worker.createWorker();
			workerOuter.submit(() -> {
				workerInner = Worker.createWorker(numberOfAsyncTotalSizeComputeWorkers());
				
				try {
					for(RemoteFile file : Utils.iterable(Stream.concat(reversedStream(segments),
					                                                   reversedStream(subtitles))
					                                           .iterator())) {
						if(!checkState()) {
							// Important to interrupt before break
							workerInner.stop();
							workerOuter.stop();
							// Exit the loop
							break;
						}
						
						long segmentSize = sync(file, RemoteFile::size);
						if(segmentSize > 0L) {
							sizeAdd(segmentSize - file.estimatedSize());
						} else {
							workerInner.submit(() -> {
								long fileSize = sync(file, RemoteFile::size);
								
								if(fileSize <= 0L) {
									for(int i = 0; fileSize < 0L && i <= maxRetryAttempts; ++i) {
										fileSize = Ignore.defaultValue(() -> sizeOf(file.uri(), HEADERS),
										                               MediaConstants.UNKNOWN_SIZE);
									}
									
									sync(file, RemoteFile::size, fileSize);
								}
								
								if(fileSize > 0L) {
									sizeAdd(fileSize - file.estimatedSize());
								}
							});
						}
					}
					
					workerInner.waitTillDone();
				} finally {
					workerInner.stop();
					workerOuter.stop();
					workerInner = null;
					workerOuter = null;
				}
			});
			
			return false;
		}
		
		@Override
		public void stop() throws Exception {
			if(workerInner != null) {
				workerInner.stop();
			}
			
			if(workerOuter != null) {
				workerOuter.stop();
			}
		}
		
		@Override
		public void pause() throws Exception {
			if(workerInner != null) {
				workerInner.pause();
			}
			
			if(workerOuter != null) {
				workerOuter.pause();
			}
		}
		
		@Override
		public void resume() throws Exception {
			if(workerInner != null) {
				workerInner.resume();
			}
			
			if(workerOuter != null) {
				workerOuter.resume();
			}
		}
	}
	
	private static interface TimeUpdateTrackerBase {
		
		void timeMs(long timeMs);
		void totalTimeMs(long totalTimeMs);
	}
	
	private static interface RemoteFile {
		
		Object value();
		URI uri();
		void size(long size);
		long size();
		void estimatedSize(long size);
		long estimatedSize();
	}
	
	private final static class RemoteMedia implements RemoteFile {
		
		private final Media media;
		private long size = MediaConstants.UNKNOWN_SIZE;
		private long estimatedSize = 0L;
		
		public RemoteMedia(Media media) {
			this.media = Objects.requireNonNull(media);
		}
		
		@Override public Object value() { return media; }
		@Override public URI uri() { return media.uri(); }
		@Override public void size(long size) { this.size = size; }
		@Override public long size() { return size; }
		@Override public void estimatedSize(long size) { this.estimatedSize = size; }
		@Override public long estimatedSize() { return estimatedSize; }
	}
	
	private final static class RemoteFileSegment implements RemoteFile {
		
		private final FileSegment segment;
		private long estimatedSize = 0L;
		
		public RemoteFileSegment(FileSegment segment) {
			this.segment = Objects.requireNonNull(segment);
		}
		
		@Override public Object value() { return segment; }
		@Override public URI uri() { return segment.uri(); }
		@Override public void size(long size) { segment.size(size); }
		@Override public long size() { return segment.size(); }
		@Override public void estimatedSize(long size) { this.estimatedSize = size; }
		@Override public long estimatedSize() { return estimatedSize; }
	}
	
	private static interface TotalSizeComputer {
		
		boolean compute(List<? extends RemoteFile> segments, List<? extends RemoteFile> subtitles) throws Exception;
		
		void pause() throws Exception;
		void resume() throws Exception;
		void stop() throws Exception;
	}
}