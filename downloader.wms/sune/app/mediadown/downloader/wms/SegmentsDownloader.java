package sune.app.mediadown.downloader.wms;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import sune.app.mediadown.TaskStates;
import sune.app.mediadown.conversion.ConversionMedia;
import sune.app.mediadown.download.Destination;
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
import sune.app.mediadown.downloader.wms.common.Common;
import sune.app.mediadown.downloader.wms.common.DownloaderState;
import sune.app.mediadown.downloader.wms.common.InternalSegmentsDownloader;
import sune.app.mediadown.downloader.wms.common.RemoteFile;
import sune.app.mediadown.downloader.wms.common.RemoteFileSegment;
import sune.app.mediadown.downloader.wms.common.RemoteMedia;
import sune.app.mediadown.downloader.wms.common.Segments;
import sune.app.mediadown.downloader.wms.parallel.ParallelSegmentsDownloader;
import sune.app.mediadown.downloader.wms.serial.SerialSegmentsDownloader;
import sune.app.mediadown.event.DownloadEvent;
import sune.app.mediadown.event.Event;
import sune.app.mediadown.event.EventRegistry;
import sune.app.mediadown.event.Listener;
import sune.app.mediadown.event.tracker.TrackerEvent;
import sune.app.mediadown.event.tracker.TrackerManager;
import sune.app.mediadown.event.tracker.WaitTracker;
import sune.app.mediadown.gui.table.ResolvedMedia;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SegmentedMedia;
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.pipeline.DownloadPipelineResult;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Opt.OptCondition;
import sune.app.mediadown.util.Range;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;
import sune.app.mediadown.util.VideoUtils;

public final class SegmentsDownloader implements Download, DownloadResult {
	
	private final TrackerManager trackerManager = new TrackerManager();
	private final EventRegistry<DownloadEvent> eventRegistry = new EventRegistry<>();
	
	private final Media media;
	private final Path dest;
	private final MediaDownloadConfiguration configuration;
	private final DownloaderState state;
	
	private DownloadPipelineResult pipelineResult;
	private List<InternalDownloader> downloaders = new ArrayList<>();
	
	SegmentsDownloader(
		Media media,
		Path dest,
		MediaDownloadConfiguration configuration,
		int maxRetryAttempts,
		int waitOnRetryMs
	) {
		this.media = Objects.requireNonNull(media);
		this.dest = Objects.requireNonNull(dest);
		this.configuration = Objects.requireNonNull(configuration);
		
		final int numOfWorkers = 4; // TODO: Make configurable
		
		this.state = new DownloaderState(
			checkMaxRetryAttempts(maxRetryAttempts),
			checkMilliseconds(waitOnRetryMs),
			numOfWorkers,
			trackerManager
		);
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
	
	private static final boolean allowOnlySuccessfulResponse(Response response) {
		return response.statusCode() == 200;
	}
	
	private static final List<? extends RemoteFile> listSegments(SegmentedMedia media) {
		return enumerateToList(((SegmentedMedia) media).segments().segments(), RemoteFileSegment::new);
	}
	
	private final void doConversion(List<ConversionMedia> inputs, ResolvedMedia output) {
		pipelineResult = DownloadPipelineResult.doConversion(inputs, output);
	}
	
	private final void estimateTotalSize(List<Segments> segmentsList) {
		state.sizeSet(segmentsList.stream().mapToLong(Segments::estimatedSize).sum());
	}
	
	private final InternalDownloader createDownloader() {
		FileDownloader downloader = new FileDownloader(new TrackerManager());
		downloader.addEventListener(DownloadEvent.UPDATE, this::onDownloadUpdate);
		downloader.addEventListener(DownloadEvent.ERROR, this::onDownloadError);
		downloaders.add(downloader);
		return downloader;
	}
	
	private final Path temporaryFile(int i) {
		String fileName = Utils.OfPath.fileName(dest);
		Path tempFile = dest.getParent().resolve(fileName + "." + i + ".part");
		Ignore.callVoid(() -> NIO.deleteFile(tempFile));
		return tempFile;
	}
	
	private final boolean doDownloadSegmentedMedia(
		SegmentedMedia media,
		Path output
	) throws Exception {
		try(InternalSegmentsDownloader segmentsDownloader = newDownloader()) {
			Segments segments = new Segments(
				media,
				listSegments((SegmentedMedia) media),
				new Destination.OfPath(output),
				MediaConstants.UNKNOWN_DURATION
			);
			
			return segmentsDownloader.download(segments);
		}
	}
	
	private final boolean doDownloadSolidMedia(
		InternalDownloader downloader, // TODO: Remove downloader argument
		Media media,
		Path output
	) throws Exception {
		Request request = Request.of(media.uri()).headers(Common.HEADERS).GET();
		downloader.start(request, output, DownloadConfiguration.ofDefault());
		return true;
	}
	
	private final boolean doDownload(
		InternalDownloader downloader, // TODO: Remove downloader argument
		Media media,
		Path output
	) throws Exception {
		return (
			media.isSegmented()
				? doDownloadSegmentedMedia((SegmentedMedia) media, output)
				: doDownloadSolidMedia(downloader, media, output)
		);
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
	
	private final void onDownloadUpdate(DownloadContext context) {
		state.onUpdate(context);
		eventRegistry.call(DownloadEvent.UPDATE, this);
	}
	
	private final void onDownloadError(DownloadContext context) {
		state.onError(context);
		eventRegistry.call(DownloadEvent.ERROR, this);
	}
	
	private final InternalSegmentsDownloader newDownloader() {
		DownloadConfiguration.Builder configurationBuilder = DownloadConfiguration.builder()
			.rangeRequest(new Range<>(0L, -1L))
			.responseFilter(SegmentsDownloader::allowOnlySuccessfulResponse);
		
		// TODO: Choose serial or parallel based on configuration
		
		InternalSegmentsDownloader segmentsDownloader = null;
		boolean parallel = true;
		
		if(parallel) {
			segmentsDownloader = new ParallelSegmentsDownloader(
				state, configurationBuilder, this::createDownloader
			);
		} else {
			segmentsDownloader = new SerialSegmentsDownloader(
				state, configurationBuilder, this::createDownloader
			);
		}
		
		// We have to translate the update event to the download update event since
		// the registry is only for the download events.
		segmentsDownloader.addEventListener(TrackerEvent.UPDATE, (o) -> {
			eventRegistry.call(DownloadEvent.UPDATE, this);
		});
		
		return segmentsDownloader;
	}
	
	private final boolean download(List<Segments> segmentsList) throws Exception {
		try(InternalSegmentsDownloader downloader = newDownloader()) {
			for(Segments segments : segmentsList) {
				if(!downloader.download(segments)) {
					return false;
				}
			}
			
			return true;
		}
	}
	
	private final InternalDownloader createSubtitlesDownloader() {
		SubtitlesRetimeStrategy retimeStrategy = SubtitlesRetimeStrategies.of(media.metadata());
		InternalDownloader downloader = new SegmentedSubtitlesDownloader(new TrackerManager(), retimeStrategy);
		downloader.addEventListener(DownloadEvent.UPDATE, this::onDownloadUpdate);
		downloader.addEventListener(DownloadEvent.ERROR, this::onDownloadError);
		downloaders.add(downloader);
		return downloader;
	}
	
	private final InternalDownloader createSubtitlesDownloader(SubtitlesMedia media) {
		return media.isSegmented() ? createSubtitlesDownloader() : createDownloader();
	}
	
	private final boolean downloadSubtitles(List<RemoteMedia> subtitles, Path destination) throws Exception {
		if(subtitles.isEmpty()) {
			return true;
		}
		
		Path directory = destination.getParent();
		String baseName = Utils.OfPath.baseName(destination);
		
		for(RemoteMedia subtitle : subtitles) {
			if(!state.check()) return false;
			
			SubtitlesMedia media = (SubtitlesMedia) subtitle.value();
			Path path = subtitlesPath(media, baseName, directory);
			
			try(InternalDownloader downloader = createSubtitlesDownloader(media)) {
				if(!doDownload(downloader, media, path)) {
					return false;
				}
			}
		}
		
		return true;
	}
	
	private final boolean convert(List<Segments> segmentsList) throws Exception {
		if(!state.check()) return false;
		
		double duration = segmentsList.stream()
			.mapToDouble(Segments::duration)
			.filter((d) -> d > 0.0)
			.min().orElse(MediaConstants.UNKNOWN_DURATION);
		
		if(duration <= 0.0) {
			// If we do not have any duration information from the segments, try to obtain it
			// from the downloaded files.
			duration = VideoUtils.tryGetDuration(
				segmentsList.stream()
					.map(Segments::destination)
					.map(Destination::path)
					.collect(Collectors.toList())
			);
		}
		
		final double finalDuration = duration;
		ResolvedMedia output = new ResolvedMedia(media, dest, configuration);
		List<ConversionMedia> inputs = segmentsList.stream()
			.map((s) -> new ConversionMedia(s.media(), s.destination().path(), finalDuration))
			.collect(Collectors.toList());
		
		doConversion(inputs, output);
		return true;
	}
	
	private static final <T, R> Stream<R> enumerate(List<T> list, BiFunction<Integer, T, R> action) {
		return IntStream.range(0, list.size())
					.mapToObj((i) -> action.apply(i, list.get(i)));
	}
	
	private static final <T, R> List<R> enumerateToList(List<T> list, BiFunction<Integer, T, R> action) {
		return enumerate(list, action).collect(Collectors.toList());
	}
	
	private final Segments mediaToSegments(int ctr, Media m) {
		double estimatedSize = MediaUtils.estimateTotalSize(m);
		List<? extends FileSegment> fileSegments = mediaToSegmentsHolder(m).segments();
		long estimate = (long) Math.ceil(estimatedSize / fileSegments.size());
		List<? extends RemoteFile> remoteFiles = enumerate(fileSegments, RemoteFileSegment::new)
			.peek((s) -> { long v; s.estimatedSize((v = s.size()) > 0L ? v : estimate); })
			.collect(Collectors.toList());
		Path output = temporaryFile(ctr);
		double duration = mediaToSegmentsHolder(m).duration();
		return new Segments(m, remoteFiles, new Destination.OfPath(output), duration);
	}
	
	@Override
	public void start() throws Exception {
		if(isRunning() || isPaused()) {
			return; // Nothing to do
		}
		
		state.reset(TaskStates.STARTED | TaskStates.RUNNING);
		trackerManager.tracker(new WaitTracker());
		eventRegistry.call(DownloadEvent.BEGIN, this);
		
		List<Segments> segmentsList = enumerateToList(segmentedMedia(media), this::mediaToSegments);
		List<RemoteMedia> subtitles = configuration.selectedMedia(MediaType.SUBTITLES).stream()
			.map(RemoteMedia::new)
			.collect(Collectors.toList());
		
		estimateTotalSize(segmentsList);
		if(!state.check()) return;
		
		trackerManager.tracker(state.downloadTracker());
		
		try {
			NIO.createFile(dest);
			if(!download(segmentsList)) return;
			if(!downloadSubtitles(subtitles, dest)) return;
			if(!convert(segmentsList)) return;
			state.set(TaskStates.DONE);
		} catch(Exception ex) {
			state.setException(ex);
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
		
		for(InternalDownloader downloader : downloaders) {
			downloader.stop();
			
			try {
				downloader.close();
			} catch(Exception ex) {
				// Ignore
			}
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
		
		for(InternalDownloader downloader : downloaders) {
			downloader.pause();
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
		
		for(InternalDownloader downloader : downloaders) {
			downloader.resume();
		}
		
		state.set(TaskStates.RUNNING);
		state.unset(TaskStates.PAUSED);
		eventRegistry.call(DownloadEvent.RESUME, this);
	}
	
	@Override
	public void close() throws Exception {
		for(InternalDownloader downloader : downloaders) {
			try {
				downloader.close();
			} catch(Exception ex) {
				// Ignore
			}
		}
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
		return state.exception();
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
		return state.sizeGet();
	}
}