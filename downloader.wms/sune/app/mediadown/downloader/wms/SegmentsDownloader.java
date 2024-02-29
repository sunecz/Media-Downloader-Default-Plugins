package sune.app.mediadown.downloader.wms;

import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
import sune.app.mediadown.downloader.wms.SegmentedSubtitlesDownloader.SubtitlesRetimeStrategies;
import sune.app.mediadown.downloader.wms.SegmentedSubtitlesDownloader.SubtitlesRetimeStrategy;
import sune.app.mediadown.event.DownloadEvent;
import sune.app.mediadown.event.Event;
import sune.app.mediadown.event.EventRegistry;
import sune.app.mediadown.event.Listener;
import sune.app.mediadown.event.tracker.DownloadTracker;
import sune.app.mediadown.event.tracker.PipelineProgress;
import sune.app.mediadown.event.tracker.PipelineStates;
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
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Opt.OptCondition;
import sune.app.mediadown.util.Pair;
import sune.app.mediadown.util.Range;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;
import sune.app.mediadown.util.VideoUtils;

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
	private final int waitOnRetryMs;
	
	private final InternalState state = new InternalState();
	private final SyncObject lockPause = new SyncObject();
	
	private TotalSizeComputer totalSizeComputer;
	private final AtomicLong size = new AtomicLong(MediaConstants.UNKNOWN_SIZE);
	private DownloadPipelineResult pipelineResult;
	private InternalDownloader downloader;
	private InternalDownloader temporaryDownloader;
	private DownloadTracker downloadTracker;
	private Exception exception;
	private DownloadConfiguration.Builder configurationBuilder;
	private DownloadEventHandler handler;
	
	SegmentsDownloader(
			Media media, Path dest, MediaDownloadConfiguration configuration, int maxRetryAttempts, int waitOnRetryMs
	) {
		this.media            = Objects.requireNonNull(media);
		this.dest             = Objects.requireNonNull(dest);
		this.configuration    = Objects.requireNonNull(configuration);
		this.maxRetryAttempts = checkMaxRetryAttempts(maxRetryAttempts);
		this.waitOnRetryMs    = checkMilliseconds(waitOnRetryMs);
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
	
	private static final Pair<Long, Version> sizeAndVersionOf(URI uri, HttpHeaders headers) throws Exception {
		Response response = Web.peek(Request.of(uri).headers(headers).HEAD());
		return new Pair<>(Web.size(response), response.version());
	}
	
	private static final Pair<Boolean, Long> sizeOrEstimatedSize(
			List<? extends RemoteFile> segments,
			List<? extends RemoteFile> subtitles
	) {
		boolean allHaveSize = stream(segments, subtitles).allMatch((s) -> s.size() > 0L);
		ToLongFunction<? super RemoteFile> mapper = allHaveSize ? RemoteFile::size : RemoteFile::estimatedSize;
		return new Pair<>(allHaveSize, stream(segments, subtitles).mapToLong(mapper).sum());
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
			Media::isPhysical,
			(m) -> !m.type().is(MediaType.SUBTITLES)
		);
	}
	
	private static final List<Media> segmentedMedia(Media media) {
		return MediaUtils.filterRecursive(media, conditionIsSegmentedAndNotSubtitles());
	}
	
	private static final FileSegmentsHolder mediaToSegmentsHolder(Media media) {
		return ((SegmentedMedia) media).segments();
	}
	
	private static final <T> Stream<T> stream(List<? extends T> a, List<? extends T> b) {
		return Stream.concat(a.stream(), b.stream());
	}
	
	private static final <T> Stream<T> reversedStream(List<? extends T> list) {
		final int from = 0, to = list.size(); // Implicit null-check
		return IntStream.range(from, to).map((i) -> to - i + from - 1).mapToObj(list::get);
	}
	
	private static final boolean allowOnlySuccessfulResponse(Response response) {
		return response.statusCode() == 200;
	}
	
	private static final List<? extends RemoteFile> listSegments(SegmentedMedia media) {
		return ((SegmentedMedia) media).segments().segments().stream()
					.map(RemoteFileSegment::new)
					.collect(Collectors.toList());
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
	
	private final void doConversion(List<ConversionMedia> inputs, ResolvedMedia output) {
		pipelineResult = DownloadPipelineResult.doConversion(inputs, output);
	}
	
	private final boolean computeTotalSize(
			List<List<? extends RemoteFile>> segments, List<? extends RemoteFile> subtitles
	) throws Exception {
		List<? extends RemoteFile> flattenSegments = segments.stream()
			.flatMap(List::stream)
			.collect(Collectors.toList());
		
		Pair<Boolean, Long> sizeResult = sizeOrEstimatedSize(flattenSegments, subtitles);
		sizeSet(sizeResult.b);
		
		// All files (segments and subtitles) have computed size (not estimated)
		if(sizeResult.a) return true;
		
		if(!MediaDownloader.configuration().computeStreamSize()) {
			return false; // Total size is not final
		}
		
		// Obtain the total size compute strategy and compute the size
		totalSizeComputer = new AsynchronousTotalSizeComputer();
		return totalSizeComputer.compute(flattenSegments, subtitles);
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
	
	private final boolean doDownload(InternalDownloader downloader, List<? extends RemoteFile> segments, Path output)
			throws Exception {
		Tracker previousTracker = null;
		RetryDownloadSimpleTracker retryTracker = null;
		long written = 0L, bytes;
		
		for(RemoteFile segment : segments) {
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
					Range<Long> rangeOutput = new Range<>(written, -1L);
					DownloadConfiguration downloadConfiguration
						= configurationBuilder.rangeOutput(rangeOutput).totalBytes(segment.size()).build();
					downloadedBytes = downloader.start(request, output, downloadConfiguration);
					error = downloader.isError() || downloadedBytes < 0L;
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
					
					if(size >= 0L) {
						sync(segment, RemoteFile::size, size);
						sizeAdd(size - segment.estimatedSize());
						
						// Check whether the downloaded size equals the total size, if not
						// just retry the download again.
						if(downloadedBytes != size) {
							error = true;
						}
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
			
			if((bytes = downloader.writtenBytes()) > 0L) {
				written += bytes;
			}
		}
		
		// Allow error propagating since there will be no more retry attempts
		handler.setPropagateError(true);
		return true;
	}
	
	private final boolean doDownload(InternalDownloader downloader, Request request, Path output) throws Exception {
		downloader.start(request, output, DownloadConfiguration.ofDefault());
		return true;
	}
	
	private final boolean doDownload(InternalDownloader downloader, Media media, Path output) throws Exception {
		return media.isSegmented()
			? doDownload(downloader, listSegments((SegmentedMedia) media), output)
			: doDownload(downloader, Request.of(media.uri()).headers(HEADERS).GET(), output);
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
	
	private final boolean download(List<List<? extends RemoteFile>> segments, List<Path> outputs) throws Exception {
		Iterator<Path> output = outputs.iterator();
		
		for(List<? extends RemoteFile> list : segments) {
			if(!doDownload(downloader, list, output.next())) {
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
			InternalDownloader downloader = this.downloader;
			
			if(media.isSegmented()) {
				SubtitlesRetimeStrategy retimeStrategy = SubtitlesRetimeStrategies.of(media.metadata());
				temporaryDownloader = new SegmentedSubtitlesDownloader(new TrackerManager(), retimeStrategy);
				temporaryDownloader.addEventListener(DownloadEvent.UPDATE, handler::onUpdate);
				temporaryDownloader.addEventListener(DownloadEvent.ERROR, handler::onError);
				temporaryDownloader.setTracker((DownloadTracker) downloader.trackerManager().tracker());
				downloader = temporaryDownloader;
			}
			
			if(!doDownload(downloader, media, path)) {
				return false;
			}
			
			if(temporaryDownloader != null) {
				temporaryDownloader.stop();
				temporaryDownloader = null;
			}
		}
		
		return true;
	}
	
	private final void stopTotalSizeComputation() throws Exception {
		if(totalSizeComputer != null) {
			totalSizeComputer.stop();
		}
	}
	
	private final boolean convert(List<Media> mediaSingles, List<FileSegmentsHolder> segmentsHolders,
			List<Path> outputs) throws Exception {
		if(!checkState()) return false;
		
		double duration = segmentsHolders.stream()
			.mapToDouble(FileSegmentsHolder::duration)
			.filter((d) -> d > 0.0)
			.min().orElse(MediaConstants.UNKNOWN_DURATION);
		
		if(duration <= 0.0) {
			// If we do not have any duration information from the segments, try to obtain it
			// from the downloaded files.
			duration = VideoUtils.tryGetDuration(outputs);
		}
		
		final double finalDuration = duration;
		ResolvedMedia output = new ResolvedMedia(media, dest, configuration);
		List<ConversionMedia> inputs = Utils.zip(mediaSingles.stream(), outputs.stream(), Pair::new)
			.map((p) -> new ConversionMedia(p.a, p.b, finalDuration))
			.collect(Collectors.toList());
		
		doConversion(inputs, output);
		return true;
	}
	
	@Override
	public void start() throws Exception {
		if(isRunning() || isPaused()) {
			return; // Nothing to do
		}
		
		state.clear(TaskStates.STARTED | TaskStates.RUNNING);
		trackerManager.tracker(new WaitTracker());
		eventRegistry.call(DownloadEvent.BEGIN, this);
		
		// Prepare the segments that should be downloaded
		List<Media> mediaSingles = segmentedMedia(media);
		List<FileSegmentsHolder> segmentsHolders = mediaSingles.stream()
			.map(SegmentsDownloader::mediaToSegmentsHolder)
			.collect(Collectors.toList());
		
		List<List<? extends RemoteFile>> segments = mediaSingles.stream().map((m) -> {
			double estimatedSize = MediaUtils.estimateTotalSize(m);
			List<? extends FileSegment> fileSegments = mediaToSegmentsHolder(m).segments();
			long est = (long) Math.ceil(estimatedSize / fileSegments.size());
			return fileSegments.stream()
						.map(RemoteFileSegment::new)
						.peek((s) -> { long v; s.estimatedSize((v = s.size()) > 0L ? v : est); })
						.collect(Collectors.toList());
		}).collect(Collectors.toList());
		
		// Prepare the subtitles that should be downloaded, may be none
		List<RemoteMedia> subtitles = configuration.selectedMedia(MediaType.SUBTITLES).stream()
			.map(RemoteMedia::new)
			.collect(Collectors.toList());
		
		computeTotalSize(segments, subtitles);
		if(!checkState()) return;
		
		downloadTracker = new DownloadTracker(size.get());
		trackerManager.tracker(downloadTracker);
		
		if(handler == null) {
			handler = new DownloadEventHandler(downloadTracker);
		}
		
		if(downloader == null) {
			downloader = new FileDownloader(new TrackerManager());
			downloader.addEventListener(DownloadEvent.UPDATE, handler::onUpdate);
			downloader.addEventListener(DownloadEvent.ERROR, handler::onError);
		}
		
		configurationBuilder = DownloadConfiguration.builder()
			.rangeRequest(new Range<>(0L, -1L))
			.responseFilter(SegmentsDownloader::allowOnlySuccessfulResponse);
		
		try {
			List<Path> tempFiles = temporaryFiles(segments.size());
			NIO.createFile(dest);
			if(!download(segments, tempFiles)) return;
			if(!downloadSubtitles(subtitles, dest)) return;
			stopTotalSizeComputation();
			if(!convert(mediaSingles, segmentsHolders, tempFiles)) return;
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
		if(!isStarted() || isStopped() || isDone()) {
			return; // Nothing to do
		}
		
		state.unset(TaskStates.RUNNING);
		state.unset(TaskStates.PAUSED);
		lockPause.unlock();
		
		if(downloader != null) {
			downloader.stop();
		}
		
		if(temporaryDownloader != null) {
			temporaryDownloader.stop();
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
		if(!isStarted() || isPaused() || isStopped() || isDone()) {
			return; // Nothing to do
		}
		
		if(downloader != null) {
			downloader.pause();
		}
		
		if(temporaryDownloader != null) {
			temporaryDownloader.pause();
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
		if(!isStarted() || !isPaused() || isStopped() || isDone()) {
			return; // Nothing to do
		}
		
		if(downloader != null) {
			downloader.resume();
		}
		
		if(temporaryDownloader != null) {
			temporaryDownloader.resume();
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
	
	private final class AsynchronousTotalSizeComputer implements TotalSizeComputer {
		
		private Worker worker;
		
		@Override
		public boolean compute(
				List<? extends RemoteFile> segments,
				List<? extends RemoteFile> subtitles
		) throws Exception {
			worker = Worker.createWorker();
			worker.submit(() -> {
				Stream<RemoteFile> files = Stream.concat(
					reversedStream(segments),
					reversedStream(subtitles)
				);
				
				try {
					for(RemoteFile file : Utils.iterable(files.iterator())) {
						if(!checkState()) {
							// Exit the loop
							break;
						}
						
						long fileSize = sync(file, RemoteFile::size);
						
						if(fileSize <= 0L) {
							for(int i = 0; fileSize < 0L && i <= maxRetryAttempts; ++i) {
								Pair<Long, Version> pair = Ignore.call(() -> sizeAndVersionOf(file.uri(), HEADERS));
								
								if(pair != null) {
									fileSize = pair.a;
									
									// Do not compute total size when HTTP/2 is not used
									if(pair.b != Version.HTTP_2) {
										return; // Do not continue
									}
								}
							}
							
							if(fileSize > 0L) {
								sync(file, RemoteFile::size, fileSize);
								sizeAdd(fileSize - file.estimatedSize());
							}
						}
					}
				} finally {
					worker.stop();
				}
			});
			
			return false;
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
		private volatile long size;
		private volatile long estimatedSize;
		
		public RemoteMedia(Media media) {
			this.media = Objects.requireNonNull(media);
			this.size = MediaConstants.UNKNOWN_SIZE;
			this.estimatedSize = 0L;
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
		private volatile long size;
		private volatile long estimatedSize;
		
		public RemoteFileSegment(FileSegment segment) {
			this.segment = Objects.requireNonNull(segment);
			this.size = segment.size();
			this.estimatedSize = 0L;
		}
		
		@Override public Object value() { return segment; }
		@Override public URI uri() { return segment.uri(); }
		@Override public void size(long size) { this.size = size; }
		@Override public long size() { return size; }
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