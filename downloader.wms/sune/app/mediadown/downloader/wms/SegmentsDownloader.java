package sune.app.mediadown.downloader.wms;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import sune.app.mediadown.Download;
import sune.app.mediadown.InternalState;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.Shared;
import sune.app.mediadown.TaskStates;
import sune.app.mediadown.convert.ConversionMedia;
import sune.app.mediadown.download.DownloadConfiguration;
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
import sune.app.mediadown.event.tracker.TrackerEvent;
import sune.app.mediadown.event.tracker.TrackerManager;
import sune.app.mediadown.event.tracker.WaitTracker;
import sune.app.mediadown.gui.table.ResolvedMedia;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.AudioMediaBase;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;
import sune.app.mediadown.media.MediaContainer;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.media.VideoMediaBase;
import sune.app.mediadown.pipeline.DownloadPipelineResult;
import sune.app.mediadown.util.CheckedSupplier;
import sune.app.mediadown.util.Metadata;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Opt.OptMapper;
import sune.app.mediadown.util.Pair;
import sune.app.mediadown.util.Range;
import sune.app.mediadown.util.Reflection2;
import sune.app.mediadown.util.Reflection3;
import sune.app.mediadown.util.SyncObject;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Worker;

public final class SegmentsDownloader implements Download, DownloadResult {
	
	private static final Map<String, String> HEADERS = Map.of("Accept", "*/*");
	private static final long TIME_UPDATE_RESOLUTION_MS = 50L;
	
	private final Translation translation = MediaDownloader.translation().getTranslation("plugin.downloader.wms");
	private final TrackerManager manager = new TrackerManager();
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
	private boolean updateRemoteFileSizes;
	private final AtomicLong size = new AtomicLong(MediaConstants.UNKNOWN_SIZE);
	private DownloadPipelineResult pipelineResult;
	private InternalDownloader downloader;
	private DownloadTracker downloadTracker;
	
	private static final ConcurrentVarLazyLoader<CookieManager> cookieManager
		= ConcurrentVarLazyLoader.of(SegmentsDownloader::ensureCookieManager);
	private static final ConcurrentVarLazyLoader<HttpClient> httpClient
		= ConcurrentVarLazyLoader.of(SegmentsDownloader::buildHttpClient);
	private static final ConcurrentVarLazyLoader<HttpRequest.Builder> httpRequestBuilder
		= ConcurrentVarLazyLoader.of(SegmentsDownloader::buildHttpRequestBuilder);
	
	SegmentsDownloader(Media media, Path dest, MediaDownloadConfiguration configuration, int maxRetryAttempts,
			boolean asyncTotalSize, int waitOnRetryMs) {
		this.media            = Objects.requireNonNull(media);
		this.dest             = Objects.requireNonNull(dest);
		this.configuration    = Objects.requireNonNull(configuration);
		this.maxRetryAttempts = checkMaxRetryAttempts(maxRetryAttempts);
		this.asyncTotalSize   = asyncTotalSize;
		this.waitOnRetryMs    = checkMilliseconds(waitOnRetryMs);
		manager.tracker(new WaitTracker());
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
	
	private static final CookieManager ensureCookieManager() throws Exception {
		Reflection3.invokeStatic(Web.class, "ensureCookieManager");
		return (CookieManager) Reflection2.getField(Web.class, null, "COOKIE_MANAGER");
	}
	
	private static final HttpClient buildHttpClient() throws Exception {
		return HttpClient.newBuilder()
				         .connectTimeout(Duration.ofMillis(5000))
				         .followRedirects(Redirect.NORMAL)
				         .cookieHandler(cookieManager.value())
				         .version(Version.HTTP_2)
				         .build();
	}
	
	private static final HttpRequest.Builder buildHttpRequestBuilder() throws Exception {
		return HttpRequest.newBuilder()
				          .method("HEAD", BodyPublishers.noBody())
				          .setHeader("User-Agent", Shared.USER_AGENT);
	}
	
	private static final long sizeOf(URI uri, Map<String, String> headers) throws Exception {
		HttpRequest request = httpRequestBuilder.value().copy().uri(uri).build();
		HttpResponse<Void> response = httpClient.value().send(request, BodyHandlers.discarding());
		return response.headers().firstValueAsLong("content-length").orElse(MediaConstants.UNKNOWN_SIZE);
	}
	
	private static final Pair<Boolean, Long> sizeOrEstimatedSize(List<RemoteFile> segments, List<RemoteFile> subtitles) {
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
	
	private static final List<Media> mediaSegmentedSingles(Media media) {
		return Opt.of(media).ifTrue(Media::isSingle).map(List::of)
				  .<Media>or((opt) -> opt.ifTrue(Media::isContainer)
				                         .map(OptMapper.of(Media::mapToContainer)
				                                       .join(MediaContainer::media)
				                                       .join(List::stream)
				                                       .join((s) -> s.map(SegmentsDownloader::mediaSegmentedSingles)
				                                                     .flatMap(List::stream)
				                                                     .collect(Collectors.toList()))
				                                       .build()))
				  .orElseGet(List::of).stream()
				  .filter(Media::isSegmented)
				  .collect(Collectors.toList());
	}
	
	private static final <T> Stream<T> stream(List<T> a, List<T> b) {
		return Stream.concat(a.stream(), b.stream());
	}
	
	private static final <T> Stream<T> reversedStream(List<T> list) {
		final int from = 0, to = list.size(); // Implicit null-check
		return IntStream.range(from, to).map((i) -> to - i + from - 1).mapToObj(list::get);
	}
	
	// Source: https://stackoverflow.com/a/23529010
	private static final <A, B, C> Stream<C> zip(Stream<? extends A> a, Stream<? extends B> b,
			BiFunction<? super A, ? super B, ? extends C> zipper) {
		Objects.requireNonNull(zipper);
		Spliterator<? extends A> aSpliterator = Objects.requireNonNull(a).spliterator();
		Spliterator<? extends B> bSpliterator = Objects.requireNonNull(b).spliterator();
		
		// Zipping looses DISTINCT and SORTED characteristics
		int characteristics = aSpliterator.characteristics()
				            & bSpliterator.characteristics()
				            & ~(Spliterator.DISTINCT | Spliterator.SORTED);
		
		long zipSize = ((characteristics & Spliterator.SIZED) != 0)
			? Math.min(aSpliterator.getExactSizeIfKnown(), bSpliterator.getExactSizeIfKnown())
			: -1L;
		
		Iterator<A> aIterator = Spliterators.iterator(aSpliterator);
		Iterator<B> bIterator = Spliterators.iterator(bSpliterator);
		Iterator<C> cIterator = new Iterator<C>() {
			
			@Override
			public boolean hasNext() {
				return aIterator.hasNext() && bIterator.hasNext();
			}
			
			@Override
			public C next() {
				return zipper.apply(aIterator.next(), bIterator.next());
			}
		};
		
		Spliterator<C> split = Spliterators.spliterator(cIterator, zipSize, characteristics);
		return StreamSupport.stream(split, a.isParallel() || b.isParallel());
	}
	
	private static final InternalDownloader createDownloader(TrackerManager manager) {
		return new FileDownloader(manager);
	}
	
	private static final String formatTime(long time, TimeUnit unit, boolean alwaysShowMs) {
		StringBuilder builder = new StringBuilder();
		boolean written = false;
		
		long hours = unit.toHours(time);
		if(hours > 0L) {
			builder.append(hours).append('h');
			time = time - unit.convert(hours, TimeUnit.HOURS);
			written = true;
		}
		
		long minutes = unit.toMinutes(time);
		if(minutes > 0L) {
			if(written) {
				builder.append(' ');
			}
			
			builder.append(minutes).append('m');
			time = time - unit.convert(minutes, TimeUnit.MINUTES);
			written = true;
		}
		
		if(written) {
			builder.append(' ');
		}
		
		long seconds = unit.toSeconds(time);
		builder.append(seconds);
		time = time - unit.convert(seconds, TimeUnit.SECONDS);
		
		long millis = unit.toMillis(time);
		if(millis > 0L || alwaysShowMs) {
			builder.append('.').append(String.format("%03d", millis));
		}
		
		builder.append('s');
		
		return builder.toString();
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
	
	private final boolean computeTotalSize(List<RemoteFile> segments, List<RemoteFile> subtitles)
			throws Exception {
		if(!MediaDownloader.configuration().computeStreamSize()) {
			// User requested no stream size computation, at least estimate the total size
			Pair<Boolean, Long> sizeResult = sizeOrEstimatedSize(segments, subtitles);
			sizeSet(sizeResult.b);
			// Allow remote file sizes to be updated as they are being downloaded
			updateRemoteFileSizes = true;
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
	
	@Override
	public final void start() throws Exception {
		if(state.is(TaskStates.RUNNING))
			return; // Nothing to do
		
		state.set(TaskStates.RUNNING);
		state.set(TaskStates.STARTED);
		
		TrackerManager dummyManager = new TrackerManager();
		downloader = createDownloader(dummyManager);
		dummyManager.addEventListener(TrackerEvent.UPDATE, (t) -> downloader.call(DownloadEvent.UPDATE, new Pair<>(downloader, dummyManager)));
		
		eventRegistry.call(DownloadEvent.BEGIN, downloader);
		manager.addEventListener(TrackerEvent.UPDATE, (t) -> eventRegistry.call(DownloadEvent.UPDATE, new Pair<>(downloader, manager)));
		Utils.ignore(() -> NIO.createFile(dest));
		
		// Prepare the segments that should be downloaded
		List<FileSegmentsHolder<?>> segmentsHolders = MediaUtils.segments(media);
		List<Media> mediaSingles = mediaSegmentedSingles(media);
		@SuppressWarnings("unchecked")
		List<RemoteFile> segments = zip(segmentsHolders.stream(), mediaSingles.stream(), Pair::new)
			.flatMap((p) -> {
				double estimatedSize = TotalSizeEstimator.estimate(p.b);
				List<FileSegment> fileSegments = (List<FileSegment>) p.a.segments();
				long est = (long) Math.ceil(estimatedSize / fileSegments.size());
				return fileSegments.stream().map(RemoteFileSegment::new).peek((s) -> s.estimatedSize(est));
			})
			.collect(Collectors.toList());
		
		// Prepare the subtitles that should be downloaded, may be none
		List<RemoteFile> subtitles = configuration.selectedMedia(MediaType.SUBTITLES).stream()
			.map(RemoteMedia::new)
			.collect(Collectors.toList());
		
		// Preapare mapping of segments, so that it can be used to map FileSegment to RemoteFile
		// in the download loop.
		Map<Object, RemoteFile> segmentsMapping = segments.stream()
			.collect(Collectors.toMap(RemoteFile::value, Function.identity()));
		
		TotalUpdateNotifyDownloadTracker notifyTracker = new TotalUpdateNotifyDownloadTracker();
		downloader.setTracker(notifyTracker);
		
		computeTotalSize(segments, subtitles);
		if(!checkState()) return;
		
		downloadTracker = new DownloadTracker(size.get());
		manager.tracker(downloadTracker);
		try {
			List<Path> tempFiles = new ArrayList<>(segmentsHolders.size());
			String fileNameNoType = Utils.fileNameNoType(dest.getFileName().toString());
			
			for(int i = 0, l = segmentsHolders.size(); i < l; ++i) {
				Path tempFile = dest.getParent().resolve(fileNameNoType + "." + i + ".part");
				Utils.ignore(() -> NIO.deleteFile(tempFile));
				tempFiles.add(tempFile);
			}
			
			DownloadEventHandler handler = new DownloadEventHandler(downloadTracker);
			downloader.addEventListener(DownloadEvent.UPDATE, handler::onUpdate);
			downloader.addEventListener(DownloadEvent.ERROR, handler::onError);
			
			DownloadConfiguration downloadConfiguration = DownloadConfiguration.ofDefault();
			Iterator<Path> tempFileIt = tempFiles.iterator();
			Range<Long> rangeRequest = new Range<>(0L, -1L);
			Tracker previousTracker = null;
			RetryDownloadSimpleTracker retryTracker = null;
			
			// Download the segments
			downloadLoop:
			for(FileSegmentsHolder<?> segmentsHolder : segmentsHolders) {
				if(!checkState()) break downloadLoop;
				
				Path tempFile = tempFileIt.next();
				long bytes = 0L;
				for(FileSegment segment : segmentsHolder.segments()) {
					if(!checkState()) break downloadLoop;
					notifyTracker.updateFile(segmentsMapping.get(segment));
					
					GetRequest request = null;
					boolean lastAttempt = false;
					boolean error = false;
					Exception exception = null;
					long downloadedBytes = 0L;
					
					for(int i = 0; (error || downloadedBytes <= 0L) && i <= maxRetryAttempts; ++i) {
						if(!checkState()) break downloadLoop;
						
						// May seem wasteful to create the request object everytime, however this
						// will update the underlying URLStreamHandler and other properties, that
						// allow to actually retry the download with "fresh" values.
						request = new GetRequest(Utils.url(segment.uri()), Shared.USER_AGENT, HEADERS);
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
								previousTracker = manager.tracker();
								manager.tracker(retryTracker);
							}
							
							retryTracker.attempt(i);
							int waitMs = (int) (waitOnRetryMs * Math.pow(i, 4.0 / 3.0));
							waitMs(waitMs, retryTracker);
							retryTracker.timeMs(-1L);
							
							if(!checkState()) break downloadLoop;
						}
						
						try {
							DownloadConfiguration config
								= new DownloadConfiguration(new Range<>(bytes, -1L), rangeRequest, segment.size());
							downloadedBytes = downloader.start(request, tempFile, config);
							error = downloader.isError() || downloadedBytes <= 0L;
						} catch(InterruptedException ex) {
							// When stopped, immediately break from the loop
							break downloadLoop;
						} catch(Exception ex) {
							error = true;
							exception = ex;
						}
						
						// Check whether the downloaded size equals the total size, if not
						// just retry the download again.
						long size = segment.size();
						if(size >= 0L && downloadedBytes != size) {
							error = true;
						}
						
						if(error) {
							if(downloadedBytes > 0L) {
								downloadTracker.update(-downloadedBytes);
							}
							
							downloadedBytes = -1L;
						}
					}
					
					if(previousTracker != null) {
						manager.tracker(previousTracker);
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
			}
			
			if(!checkState()) return;
			// Download subtitles, if any
			if(!subtitles.isEmpty()) {
				Path subtitlesDir = dest.getParent();
				String subtitlesFileName = Utils.fileNameNoType(dest.getFileName().toString());
				
				for(RemoteFile subtitle : subtitles) {
					if(!checkState()) break;
					
					notifyTracker.updateFile(subtitle);
					SubtitlesMedia sm = (SubtitlesMedia) ((RemoteMedia) subtitle).media;
					String subtitleType = Opt.of(sm.format().fileExtensions())
						.ifFalse(List::isEmpty).map((l) -> l.get(0))
						.orElseGet(() -> Utils.OfPath.info(sm.uri().toString()).extension());
					String subtitleLanguage = sm.language().codes().stream()
						.sorted(SegmentsDownloader::compareFirstLongestString)
						.findFirst().orElse(null);
					String subtitleFileName = subtitlesFileName
						+ (subtitleLanguage != null ? '.' + subtitleLanguage : "")
						+ (!subtitleType.isEmpty() ? '.' + subtitleType : "");
					Path subDest = subtitlesDir.resolve(subtitleFileName);
					GetRequest request = new GetRequest(Utils.url(sm.uri()), Shared.USER_AGENT, HEADERS);
					downloader.start(request, subDest, downloadConfiguration);
				}
			}
			
			if(!checkState()) return;
			// Compute the duration of all segments files and select the maximum
			double duration = segmentsHolders.stream()
				.mapToDouble(FileSegmentsHolder::duration)
				.max().orElse(Double.NaN);
			// Convert the segments files into the final file
			ResolvedMedia output = new ResolvedMedia(media, dest, configuration);
			List<ConversionMedia> inputs = zip(mediaSingles.stream(), tempFiles.stream(), Pair::new)
				.map((p) -> new ConversionMedia(p.a, p.b, Double.NaN))
				.collect(Collectors.toList());
			doConversion(output, duration, inputs);
			
			state.set(TaskStates.DONE);
		} catch(Exception ex) {
			eventRegistry.call(DownloadEvent.ERROR, new Pair<>(downloader, ex));
			throw ex; // Forward the exception
		} finally {
			stop();
		}
	}
	
	@Override
	public final void stop() throws Exception {
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
		
		eventRegistry.call(DownloadEvent.END, downloader);
	}
	
	@Override
	public final void pause() throws Exception {
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
		eventRegistry.call(DownloadEvent.PAUSE, downloader);
	}
	
	@Override
	public final void resume() throws Exception {
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
		eventRegistry.call(DownloadEvent.RESUME, downloader);
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
	public final boolean isRunning() {
		return state.is(TaskStates.RUNNING);
	}
	
	@Override
	public final boolean isStarted() {
		return state.is(TaskStates.STARTED);
	}
	
	@Override
	public final boolean isDone() {
		return state.is(TaskStates.DONE);
	}
	
	@Override
	public final boolean isPaused() {
		return state.is(TaskStates.PAUSED);
	}
	
	@Override
	public final boolean isStopped() {
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
	
	private final class DownloadEventHandler {
		
		private final DownloadTracker tracker;
		private final AtomicLong lastSize = new AtomicLong();
		private boolean propagateError = true;
		
		public DownloadEventHandler(DownloadTracker tracker) {
			this.tracker = Objects.requireNonNull(tracker);
		}
		
		public void onUpdate(Pair<InternalDownloader, TrackerManager> pair) {
			DownloadTracker downloadTracker = (DownloadTracker) pair.b.tracker();
			long current = downloadTracker.current();
			long delta = current - lastSize.get();
			
			tracker.update(delta);
			lastSize.set(current);
		}
		
		public void onError(Pair<InternalDownloader, Exception> pair) {
			if(!propagateError)
				return; // Ignore the error, if needed (used for retries)
			
			eventRegistry.call(DownloadEvent.ERROR, pair);
		}
		
		public void setPropagateError(boolean value) {
			propagateError = value;
		}
	}
	
	private final class TotalUpdateNotifyDownloadTracker extends DownloadTracker {
		
		private RemoteFile file;
		
		public TotalUpdateNotifyDownloadTracker() {
			super();
		}
		
		public void updateFile(RemoteFile file) {
			this.file = file;
		}
		
		@Override
		public void updateTotal(long bytes) {
			super.updateTotal(bytes);
			
			if(updateRemoteFileSizes) {
				sync(file, RemoteFile::size, bytes);
			}
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
					"time", formatTime(timeMs, TimeUnit.MILLISECONDS, true),
					"total_time", formatTime(totalTimeMs, TimeUnit.MILLISECONDS, true));
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
		public boolean compute(List<RemoteFile> segments, List<RemoteFile> subtitles) throws Exception {
			updateRemoteFileSizes = false;
			Pair<Boolean, Long> sizeResult = sizeOrEstimatedSize(segments, subtitles);
			sizeSet(sizeResult.b);
			
			// All files (segments and subtitles) have computed size (not estimated)
			if(sizeResult.a) return true;
			
			String text = translation.getSingle("progress.compute_total_size");
			PlainTextTracker tracker = new PlainTextTracker();
			manager.tracker(tracker);
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
								fileSize = Utils.ignore(() -> sizeOf(file.uri(), HEADERS), MediaConstants.UNKNOWN_SIZE);
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
		public boolean compute(List<RemoteFile> segments, List<RemoteFile> subtitles) throws Exception {
			updateRemoteFileSizes = true;
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
										fileSize = Utils.ignore(() -> sizeOf(file.uri(), HEADERS), MediaConstants.UNKNOWN_SIZE);
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
		
		boolean compute(List<RemoteFile> segments, List<RemoteFile> subtitles) throws Exception;
		
		void pause() throws Exception;
		void resume() throws Exception;
		void stop() throws Exception;
	}
	
	private static final class TotalSizeEstimator {
		
		/*
		 * [1] BitRate approximation table for video with aspect ratio 16:9.
		 * Source: https://www.circlehd.com/blog/how-to-calculate-video-file-size
		 * +---------+----------+
		 * | Quality | BitRate  |
		 * +---------+----------+
		 * | 2160p   |  20 Mbps |
		 * | 1080p   |   5 Mbps |
		 * |  720p   |   1 Mbps |
		 * |  480p   | 0.5 Mbps |
		 * +---------+----------+
		 * 
		 * [2] BitRate approximation table for audio with 2 channels and bit depth of 16.
		 * Source: https://www.omnicalculator.com/other/audio-file-size
		 * +-------------+-------------+
		 * | Sample rate | BitRate     |
		 * +-------------+-------------+
		 * | 96.00 kHz   | 3072.0 kbps |
		 * | 48.00 kHz   | 1536.0 kbps |
		 * | 44.10 kHz   | 1411.2 kbps |
		 * | 22.05 kHz   |  705.6 kbps |
		 * +-------------+-------------+
		 */
		
		// Returns values in bps
		private static final int bitRateMbpsToBandwidth(double bitRate) {
			return Math.max(0, (int) Math.ceil(bitRate * 1024.0 * 1024.0));
		}
		
		// Returns values in Mbps
		private static final int approximateBandwidthFromVideoHeight(int height) {
			final double x = height / 120.0;
			// Handle special cases where the monotonicity of quadratic regression
			// is not preserved, i.e. in the range of <0, 1).
			if(height <= 120) return bitRateMbpsToBandwidth(0.125 * x); // Use linear interpolation
			// Quadratic regression for the approximation table [1] and some additional values
			// to ensure the positivity of resulting values.
			// Values {x,y} used: {0,0},{1,0.125},{2,0.25},{3,0.375},{4,0.5},{6,1},{9,5},{18,20}.
			return bitRateMbpsToBandwidth(0.069617 * x * x - 0.142703 * x + 0.0745748);
		}
		
		// Returns values in Mbps
		private static final int approximateBandwidthFromAudioSampleRate(int sampleRate) {
			// Approximation based on the approximation table [2].
			return bitRateMbpsToBandwidth(sampleRate / 1000.0 * 32.0 / 1024.0);
		}
		
		private static final int approximateBandwidth(VideoMediaBase video) {
			return approximateBandwidthFromVideoHeight(video.resolution().height());
		}
		
		private static final int approximateBandwidth(AudioMediaBase audio) {
			return approximateBandwidthFromAudioSampleRate(audio.sampleRate());
		}
		
		private static final double fromBandwidth(int bandwidth, double duration) {
			return bandwidth / 8 * duration;
		}
		
		public static final double estimate(VideoMediaBase video) {
			return video.bandwidth() <= 0
						? fromBandwidth(approximateBandwidth(video), video.duration())
						: fromBandwidth(video.bandwidth(), video.duration());
		}
		
		public static final double estimate(AudioMediaBase audio) {
			return audio.bandwidth() <= 0
						? fromBandwidth(approximateBandwidth(audio), audio.duration())
						: fromBandwidth(audio.bandwidth(), audio.duration());
		}
		
		public static final double estimate(Media media) {
			MediaType type = media.type();
			if(type.is(MediaType.VIDEO)) return estimate((VideoMediaBase) media);
			if(type.is(MediaType.AUDIO)) return estimate((AudioMediaBase) media);
			return MediaConstants.UNKNOWN_SIZE;
		}
	}
	
	private static final class ConcurrentVarLazyLoader<T> {
		
		private final AtomicBoolean isSet = new AtomicBoolean();
		private final AtomicBoolean isSetting = new AtomicBoolean();
		private volatile T value;
		
		private final CheckedSupplier<T> supplier;
		
		private ConcurrentVarLazyLoader(CheckedSupplier<T> supplier) {
			this.supplier = Objects.requireNonNull(supplier);
		}
		
		public static final <T> ConcurrentVarLazyLoader<T> of(CheckedSupplier<T> supplier) {
			return new ConcurrentVarLazyLoader<>(supplier);
		}
		
		public final T value() throws Exception {
			if(isSet.get()) return value; // Already set
			
			while(!isSet.get()
						&& !isSetting.compareAndSet(false, true)) {
				synchronized(isSetting) {
					try {
						isSetting.wait();
					} catch(InterruptedException ex) {
						// Ignore
					}
				}
				if(isSet.get()) return value; // Already set
			}
			
			try {
				value = supplier.get();
				isSet.set(true);
				return value;
			} finally {
				isSetting.set(false);
				synchronized(isSetting) {
					isSetting.notifyAll();
				}
			}
		}
	}
}