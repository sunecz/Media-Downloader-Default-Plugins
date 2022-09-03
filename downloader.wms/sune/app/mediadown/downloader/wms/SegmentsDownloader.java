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
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.Shared;
import sune.app.mediadown.convert.ConversionConfiguration;
import sune.app.mediadown.download.IInternalDownloader;
import sune.app.mediadown.download.IInternalListener;
import sune.app.mediadown.download.MediaDownloadConfiguration;
import sune.app.mediadown.download.SingleFileDownloader;
import sune.app.mediadown.download.SingleFileDownloaderConfiguration;
import sune.app.mediadown.download.segment.FileSegment;
import sune.app.mediadown.download.segment.FileSegmentsHolder;
import sune.app.mediadown.event.DownloadEvent;
import sune.app.mediadown.event.EventRegistry;
import sune.app.mediadown.event.EventType;
import sune.app.mediadown.event.Listener;
import sune.app.mediadown.event.tracker.DownloadTracker;
import sune.app.mediadown.event.tracker.PlainTextTracker;
import sune.app.mediadown.event.tracker.TrackerManager;
import sune.app.mediadown.event.tracker.WaitTracker;
import sune.app.mediadown.gui.Dialog;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.AudioMediaBase;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;
import sune.app.mediadown.media.MediaContainer;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.media.VideoMediaBase;
import sune.app.mediadown.pipeline.DownloadPipelineResult;
import sune.app.mediadown.util.CheckedSupplier;
import sune.app.mediadown.util.EventUtils;
import sune.app.mediadown.util.FXUtils;
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

public final class SegmentsDownloader implements Download {
	
	private static final Map<String, String> HEADERS = Web.headers("Accept=*/*");
	
	private final Translation translation = MediaDownloader.translation().getTranslation("plugin.downloader.wms");
	private final TrackerManager manager = new TrackerManager();
	private final EventRegistry<DownloadEvent> eventRegistry = new EventRegistry<>();
	
	private final Media media;
	private final Path dest;
	private final MediaDownloadConfiguration configuration;
	private final int maxRetryAttempts;
	private final boolean asyncTotalSize;
	
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean done = new AtomicBoolean();
	private final AtomicBoolean paused = new AtomicBoolean();
	private final AtomicBoolean stopped = new AtomicBoolean();
	private final SyncObject lockPause = new SyncObject();
	
	private TotalSizeComputer totalSizeComputer;
	private boolean updateRemoteFileSizes;
	private final AtomicLong size = new AtomicLong(MediaConstants.UNKNOWN_SIZE);
	private DownloadPipelineResult pipelineResult;
	private IInternalDownloader downloader;
	private DownloadTracker downloadTracker;
	
	private static final ConcurrentVarLazyLoader<CookieManager> cookieManager
		= ConcurrentVarLazyLoader.of(SegmentsDownloader::ensureCookieManager);
	private static final ConcurrentVarLazyLoader<HttpClient> httpClient
		= ConcurrentVarLazyLoader.of(SegmentsDownloader::buildHttpClient);
	private static final ConcurrentVarLazyLoader<HttpRequest.Builder> httpRequestBuilder
		= ConcurrentVarLazyLoader.of(SegmentsDownloader::buildHttpRequestBuilder);
	
	SegmentsDownloader(Media media, Path dest, MediaDownloadConfiguration configuration, int maxRetryAttempts,
			boolean asyncTotalSize) {
		this.media            = Objects.requireNonNull(media);
		this.dest             = Objects.requireNonNull(dest);
		this.configuration    = Objects.requireNonNull(configuration);
		this.maxRetryAttempts = checkMaxRetryAttempts(maxRetryAttempts);
		this.asyncTotalSize   = asyncTotalSize;
		manager.setTracker(new WaitTracker());
	}
	
	private static final int checkMaxRetryAttempts(int maxRetryAttempts) {
		if(maxRetryAttempts < 0)
			throw new IllegalArgumentException();
		return maxRetryAttempts;
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
	
	private final boolean checkIfCanContinue() {
		// Wait for resume, if paused
		if(isPaused())
			lockPause.await();
		// If already not running, do not continue
		return running.get();
	}
	
	private final void doConversion(Path dest, double duration, Path... inputs) {
		ConversionConfiguration configuration = new ConversionConfiguration(dest, duration);
		String fileName = dest.getFileName().toString();
		String fileNameNoType = Utils.fileNameNoType(fileName);
		String fileType = Utils.fileType(fileName);
		String outputName = fileNameNoType + ".convert." + fileType;
		Path output = dest.getParent().resolve(outputName);
		MediaFormat formatIn = media.format();
		MediaFormat formatOut = MediaFormat.fromPath(output);
		pipelineResult = DownloadPipelineResult.doConversion(configuration, formatIn, formatOut, output, inputs);
	}
	
	private final IInternalDownloader createDownloader(TrackerManager manager) {
		return new SingleFileDownloader(manager, new SingleFileDownloaderConfiguration(true, true));
	}
	
	private final boolean showRetrySegmentDownloadPromptDialog() {
		String title = translation.getSingle("prompts.retry_segment_download.title");
		String text  = translation.getSingle("prompts.retry_segment_download.text");
		return Dialog.showPrompt(title, text);
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
		if(downloadTracker != null)
			downloadTracker.updateTotal(value);
	}
	
	private final void sizeAdd(long value) {
		long current = size.addAndGet(value);
		if(downloadTracker != null)
			downloadTracker.updateTotal(current);
	}
	
	@Override
	public final void start() throws Exception {
		if(running.get()) return; // Nothing to do
		running.set(true);
		started.set(true);
		eventRegistry.call(DownloadEvent.BEGIN, this);
		manager.setUpdateListener(() -> eventRegistry.call(DownloadEvent.UPDATE, new Pair<>(this, manager)));
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
					return fileSegments.stream()
								.map(RemoteFileSegment::new)
								.peek((s) -> s.estimatedSize(est));
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
		boolean sizeComputed = computeTotalSize(segments, subtitles);
		if(!checkIfCanContinue()) return;
		
		downloadTracker = new DownloadTracker(size.get(), sizeComputed);
		manager.setTracker(downloadTracker);
		List<Path> tempFiles = new ArrayList<>(segmentsHolders.size());
		try {
			String fileNameNoType = Utils.fileNameNoType(dest.getFileName().toString());
			for(int i = 0, l = segmentsHolders.size(); i < l; ++i) {
				Path tempFile = dest.getParent().resolve(fileNameNoType + "." + i + ".part");
				Utils.ignore(() -> NIO.deleteFile(tempFile));
				tempFiles.add(tempFile);
			}
			
			SD_IInternalListener internalListener = new SD_IInternalListener(downloadTracker);
			TrackerManager dummyManager = new TrackerManager();
			downloader = createDownloader(dummyManager);
			downloader.setTracker(notifyTracker);
			EventUtils.mapListeners(DownloadEvent.class, downloader, internalListener.toEventMapper());
			dummyManager.setUpdateListener(() -> downloader.call(DownloadEvent.UPDATE, new Pair<>(this, dummyManager)));
			Iterator<Path> tempFileIt = tempFiles.iterator();
			
			// Download the segments
			for(FileSegmentsHolder<?> segmentsHolder : segmentsHolders) {
				if(!checkIfCanContinue()) break;
				Path tempFile = tempFileIt.next();
				long bytes = 0L;
				for(FileSegment segment : segmentsHolder.segments()) {
					if(!checkIfCanContinue()) break;
					notifyTracker.updateFile(segmentsMapping.get(segment));
					GetRequest request = new GetRequest(Utils.url(segment.uri()), Shared.USER_AGENT, HEADERS);
					
					boolean shouldRetry = false;
					boolean lastAttempt = false;
					long downloadedBytes = 0L;
					do {
						for(int i = 0; downloadedBytes <= 0L && i <= maxRetryAttempts; ++i) {
							lastAttempt = i == maxRetryAttempts;
							internalListener.setPropagateError(lastAttempt);
							downloadedBytes = downloader.start(request, tempFile, this, segment.size(),
								new Range<>(0L, -1L), new Range<>(bytes, -1L));
						}
						// If even the last attempt failed, ask the user whether the download should be retried again.
						if(lastAttempt && downloadedBytes <= 0L) {
							shouldRetry = FXUtils.fxTaskValue(this::showRetrySegmentDownloadPromptDialog);
						}
					} while(shouldRetry);
					
					if(downloadedBytes > 0L) {
						bytes += downloadedBytes;
					}
				}
				// Allow error propagating since there will be no more retry attempts
				internalListener.setPropagateError(true);
			}
			
			if(!checkIfCanContinue()) return;
			// Download subtitles, if any
			if(!subtitles.isEmpty()) {
				Path subtitlesDir = dest.getParent();
				String subtitlesFileName = Utils.fileNameNoType(dest.getFileName().toString());
				for(RemoteFile subtitle : subtitles) {
					if(!checkIfCanContinue()) break;
					notifyTracker.updateFile(subtitle);
					SubtitlesMedia sm = (SubtitlesMedia) ((RemoteMedia) subtitle).media;
					String subtitleType = Opt.of(sm.format().fileExtensions())
							.ifFalse(List::isEmpty).map((l) -> l.get(0))
							.orElseGet(() -> Utils.fileType(sm.uri().toString()));
					String subtitleLanguage = sm.language().codes().stream()
							.sorted(SegmentsDownloader::compareFirstLongestString)
							.findFirst().orElse(null);
					String subtitleFileName = subtitlesFileName
							+ (subtitleLanguage != null ? '.' + subtitleLanguage : "")
							+ (!subtitleType.isEmpty() ? '.' + subtitleType : "");
					Path subDest = subtitlesDir.resolve(subtitleFileName);
					GetRequest request = new GetRequest(Utils.url(sm.uri()), Shared.USER_AGENT, HEADERS);
					downloader.start(request, subDest, this);
				}
			}
			
			if(!checkIfCanContinue()) return;
			// Compute the duration of all segments files and select the maximum
			double duration = segmentsHolders.stream()
					.mapToDouble(FileSegmentsHolder::duration)
					.max().orElse(Double.NaN);
			// Convert the segments files into the final file
			doConversion(dest, duration, Utils.toArray(tempFiles, Path.class));
			done.set(true);
		} catch(Exception ex) {
			eventRegistry.call(DownloadEvent.ERROR, new Pair<>(this, ex));
			throw ex; // Forward the exception
		} finally {
			stop();
		}
	}
	
	@Override
	public final void stop() throws Exception {
		if(stopped.get()) return; // Nothing to do
		running.set(false);
		paused .set(false);
		lockPause.unlock();
		if(downloader != null)
			downloader.stop();
		if(totalSizeComputer != null)
			totalSizeComputer.stop();
		if(!done.get())
			stopped.set(true);
		eventRegistry.call(DownloadEvent.END, this);
	}
	
	@Override
	public final void pause() throws Exception {
		if(paused.get()) return; // Nothing to do
		if(downloader != null)
			downloader.pause();
		if(totalSizeComputer != null)
			totalSizeComputer.pause();
		running.set(false);
		paused .set(true);
		eventRegistry.call(DownloadEvent.PAUSE, this);
	}
	
	@Override
	public final void resume() throws Exception {
		if(!paused.get()) return; // Nothing to do
		if(downloader != null)
			downloader.resume();
		if(totalSizeComputer != null)
			totalSizeComputer.resume();
		paused .set(false);
		running.set(true);
		lockPause.unlock();
		eventRegistry.call(DownloadEvent.RESUME, this);
	}
	
	@Override
	public DownloadPipelineResult getResult() {
		return pipelineResult;
	}
	
	@Override
	public final boolean isRunning() {
		return running.get();
	}
	
	@Override
	public final boolean isStarted() {
		return started.get();
	}
	
	@Override
	public final boolean isDone() {
		return done.get();
	}
	
	@Override
	public final boolean isPaused() {
		return paused.get();
	}
	
	@Override
	public final boolean isStopped() {
		return stopped.get();
	}
	
	@Override
	public final <E> void addEventListener(EventType<DownloadEvent, E> type, Listener<E> listener) {
		eventRegistry.add(type, listener);
	}
	
	@Override
	public final <E> void removeEventListener(EventType<DownloadEvent, E> type, Listener<E> listener) {
		eventRegistry.remove(type, listener);
	}
	
	private final class SD_IInternalListener implements IInternalListener {
		
		private final DownloadTracker tracker;
		private final AtomicLong lastSize = new AtomicLong();
		private boolean propagateError = true;
		
		public SD_IInternalListener(DownloadTracker tracker) {
			this.tracker = tracker;
		}
		
		@Override
		public <E> Listener<E> beginListener() {
			return ((o) -> {});
		}
		
		@Override
		public <E> Listener<E> updateListener() {
			return ((o) -> {
				@SuppressWarnings("unchecked")
				Pair<Download, TrackerManager> pair = (Pair<Download, TrackerManager>) o;
				DownloadTracker downloadTracker = (DownloadTracker) pair.b.getTracker();
				long current = downloadTracker.getCurrent();
				long delta   = current - lastSize.get();
				tracker.update(delta);
				lastSize.set(current);
			});
		}
		
		@Override
		public <E> Listener<E> endListener() {
			return ((o) -> {});
		}
		
		@Override
		public <E> Listener<E> errorListener() {
			return ((o) -> {
				if(!propagateError) return; // Ignore the error, if needed (used for retries)
				@SuppressWarnings("unchecked")
				Pair<Download, Exception> pair = (Pair<Download, Exception>) o;
				eventRegistry.call(DownloadEvent.ERROR, pair);
			});
		}
		
		public final void setPropagateError(boolean value) {
			propagateError = value;
		}
	}
	
	private final class TotalUpdateNotifyDownloadTracker extends DownloadTracker {
		
		private RemoteFile file;
		
		public TotalUpdateNotifyDownloadTracker() {
			super(MediaConstants.UNKNOWN_SIZE, false);
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
			manager.setTracker(tracker);
			tracker.setText(text);
			tracker.setProgress(0.0);
			
			double count = segments.size() + subtitles.size();
			AtomicInteger counter = new AtomicInteger();
			worker = Worker.createThreadedWorker();
			try {
				for(RemoteFile file : Utils.iterable(Stream.concat(segments.stream(), subtitles.stream()).iterator())) {
					if(!checkIfCanContinue()) {
						// Important to interrupt before break
						worker.interrupt();
						// Exit the loop
						break;
					}
					
					long segmentSize = file.size();
					if(segmentSize > 0L) {
						sizeAdd(segmentSize - file.estimatedSize());
						tracker.setProgress(counter.incrementAndGet() / count);
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
							
							tracker.setProgress(counter.incrementAndGet() / count);
						});
					}
				}
				
				worker.waitTillDone();
				return true;
			} finally {
				worker.interrupt();
				worker = null;
			}
		}
		
		@Override
		public void stop() throws Exception {
			if(worker != null)
				worker.interrupt();
		}
		
		@Override
		public void pause() throws Exception {
			if(worker != null)
				worker.pause();
		}
		
		@Override
		public void resume() throws Exception {
			if(worker != null)
				worker.resume();
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
						if(!checkIfCanContinue()) {
							// Important to interrupt before break
							workerInner.interrupt();
							workerOuter.interrupt();
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
					workerInner.interrupt();
					workerOuter.interrupt();
					workerInner = null;
					workerOuter = null;
				}
			});
			return false;
		}
		
		@Override
		public void stop() throws Exception {
			if(workerInner != null)
				workerInner.interrupt();
			if(workerOuter != null)
				workerOuter.interrupt();
		}
		
		@Override
		public void pause() throws Exception {
			if(workerInner != null)
				workerInner.pause();
			if(workerOuter != null)
				workerOuter.pause();
		}
		
		@Override
		public void resume() throws Exception {
			if(workerInner != null)
				workerInner.resume();
			if(workerOuter != null)
				workerOuter.resume();
		}
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