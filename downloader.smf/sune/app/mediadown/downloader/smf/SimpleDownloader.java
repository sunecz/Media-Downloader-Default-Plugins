package sune.app.mediadown.downloader.smf;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sune.app.mediadown.InternalState;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.TaskStates;
import sune.app.mediadown.concurrent.SyncObject;
import sune.app.mediadown.concurrent.Worker;
import sune.app.mediadown.conversion.ConversionMedia;
import sune.app.mediadown.download.AcceleratedFileDownloader;
import sune.app.mediadown.download.Download;
import sune.app.mediadown.download.DownloadConfiguration;
import sune.app.mediadown.download.DownloadContext;
import sune.app.mediadown.download.DownloadResult;
import sune.app.mediadown.download.InternalDownloader;
import sune.app.mediadown.download.MediaDownloadConfiguration;
import sune.app.mediadown.event.DownloadEvent;
import sune.app.mediadown.event.Event;
import sune.app.mediadown.event.EventRegistry;
import sune.app.mediadown.event.Listener;
import sune.app.mediadown.event.tracker.DownloadTracker;
import sune.app.mediadown.event.tracker.PlainTextTracker;
import sune.app.mediadown.event.tracker.TrackerManager;
import sune.app.mediadown.event.tracker.WaitTracker;
import sune.app.mediadown.gui.table.ResolvedMedia;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.pipeline.DownloadPipelineResult;
import sune.app.mediadown.util.Metadata;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Pair;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;
import sune.app.mediadown.util.VideoUtils;

public final class SimpleDownloader implements Download, DownloadResult {
	
	private static final HttpHeaders HEADERS = Web.Headers.ofSingle("Accept", "*/*");
	
	private final Translation translation = MediaDownloader.translation().getTranslation("plugin.downloader.smf");
	private final TrackerManager trackerManager = new TrackerManager();
	private final EventRegistry<DownloadEvent> eventRegistry = new EventRegistry<>();
	
	private final Media media;
	private final Path dest;
	private final MediaDownloadConfiguration configuration;
	
	private final InternalState state = new InternalState();
	private final SyncObject lockPause = new SyncObject();
	
	private Worker worker;
	private long size = MediaConstants.UNKNOWN_SIZE;
	private DownloadPipelineResult pipelineResult;
	private InternalDownloader downloader;
	private DownloadTracker downloadTracker;
	private Exception exception;
	
	SimpleDownloader(Media media, Path dest, MediaDownloadConfiguration configuration) {
		this.media         = Objects.requireNonNull(media);
		this.dest          = Objects.requireNonNull(dest);
		this.configuration = Objects.requireNonNull(configuration);
		trackerManager.tracker(new WaitTracker());
	}
	
	private static final int compareFirstLongestString(String a, String b) {
		int cmp; return (cmp = Integer.compare(b.length(), a.length())) == 0 ? 1 : cmp;
	}
	
	private static final InternalDownloader createDownloader(TrackerManager manager) {
		return new AcceleratedFileDownloader(manager);
	}
	
	private final boolean checkState() {
		// Check if paused
		if(isPaused()) lockPause.await();
		// Check if running
		return state.is(TaskStates.RUNNING);
	}
	
	private final boolean computeTotalSize(List<MediaHolder> mediaHolders, List<MediaHolder> subtitles) {
		if(!MediaDownloader.configuration().computeStreamSize())
			return false;
		String text = translation.getSingle("progress.compute_total_size");
		PlainTextTracker tracker = new PlainTextTracker();
		trackerManager.tracker(tracker);
		tracker.text(text);
		tracker.progress(0.0);
		worker = Worker.createThreadedWorker();
		size = 0L; // Reset the size
		double count = mediaHolders.size() + subtitles.size();
		AtomicInteger counter = new AtomicInteger();
		AtomicLong theSize = new AtomicLong();
		try {
			for(MediaHolder mh : Utils.iterable(Stream.concat(mediaHolders.stream(), subtitles.stream()).iterator())) {
				if(!checkState()) {
					// Important to interrupt before break
					worker.stop();
					// Exit the loop
					break;
				}
				long mediaSize = mh.size();
				if(mediaSize > 0L) {
					theSize.getAndAdd(mediaSize);
					tracker.progress(counter.incrementAndGet() / count);
				} else {
					worker.submit(() -> {
						Request request = Request.of(mh.media().uri()).headers(HEADERS).HEAD();
						long size = Ignore.defaultValue(() -> Web.size(request), MediaConstants.UNKNOWN_SIZE);
						
						if(size <= 0L) {
							// If the size is still unknown, try to estimate it
							size = (long) MediaUtils.estimateTotalSize(mh.media());
						}
						
						// Since we use AcceleratedFileDownloader, treat zero bytes as unknown
						if(size > 0L) {
							theSize.getAndAdd(size);
							mh.size(size);
						}
						
						tracker.progress(counter.incrementAndGet() / count);
					});
				}
			}
			worker.waitTillDone();
			size = theSize.get();
			return true;
		} finally {
			worker.stop();
			worker = null;
		}
	}
	
	private final void noConversion(ResolvedMedia output, ConversionMedia input) throws IOException {
		NIO.moveForce(input.path(), output.path());
		pipelineResult = DownloadPipelineResult.noConversion();
	}
	
	private final void doConversion(ResolvedMedia output, double duration, List<ConversionMedia> inputs) {
		pipelineResult = DownloadPipelineResult.doConversion(output, inputs, Metadata.of("duration", duration));
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
	
	private final boolean doDownload(MediaHolder mediaHolder, Path output) throws Exception {
		downloader.start(
			Request.of(mediaHolder.media().uri()).headers(HEADERS).GET(),
			output,
			DownloadConfiguration.ofTotalBytes(mediaHolder.size())
		);
		return true;
	}
	
	private final Path subtitlesPath(SubtitlesMedia media, String fileName, Path directory) {
		String extension = Opt.of(media.format().fileExtensions())
			.ifFalse(List::isEmpty).map((l) -> l.get(0))
			.orElseGet(() -> Utils.OfPath.info(media.uri().toString()).extension());
		String language = media.language().codes().stream()
			.sorted(SimpleDownloader::compareFirstLongestString)
			.findFirst().orElse(null);
		
		return directory.resolve(Utils.OfString.concat(
			".", Utils.OfString::nonEmpty, fileName, language, extension
		));
	}
	
	private final boolean download(List<MediaHolder> mediaHolders, List<Path> outputs) throws Exception {
		Iterator<Path> output = outputs.iterator();
		
		for(MediaHolder mediaHolder : mediaHolders) {
			if(!checkState() || !doDownload(mediaHolder, output.next())) {
				return false;
			}
		}
		
		return true;
	}
	
	private final boolean downloadSubtitles(List<MediaHolder> subtitles, Path destination) throws Exception {
		if(subtitles.isEmpty()) {
			return true;
		}
		
		Path directory = destination.getParent();
		String baseName = Utils.OfPath.baseName(destination);
		
		for(MediaHolder subtitle : subtitles) {
			if(!checkState()) return false;
			
			SubtitlesMedia media = (SubtitlesMedia) subtitle.media();
			Path path = subtitlesPath(media, baseName, directory);
			
			if(!doDownload(subtitle, path)) {
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
		
		downloader = createDownloader(new TrackerManager());
		downloader.setTracker(new DownloadTracker());
		Ignore.callVoid(() -> NIO.createFile(dest));
		
		List<MediaHolder> mediaHolders = MediaUtils.solids(media).stream()
			.filter((m) -> m.type().is(MediaType.VIDEO) || m.type().is(MediaType.AUDIO))
			.collect(Collectors.toMap(Media::uri, Function.identity(), (a, b) -> a))
			.values().stream()
			.map(MediaHolder::new)
			.collect(Collectors.toList());
		
		// Prepare the subtitles that should be downloaded, may be none
		List<MediaHolder> subtitles = configuration.selectedMedia(MediaType.SUBTITLES).stream()
			.map(MediaHolder::new)
			.collect(Collectors.toList());
		
		computeTotalSize(mediaHolders, subtitles);
		if(!checkState()) return;
		
		downloadTracker = new DownloadTracker(size);
		trackerManager.tracker(downloadTracker);
		
		try {
			List<Path> tempFiles = temporaryFiles(mediaHolders.size());
			
			DownloadEventHandler handler = new DownloadEventHandler(downloadTracker);
			downloader.addEventListener(DownloadEvent.UPDATE, handler::onUpdate);
			downloader.addEventListener(DownloadEvent.ERROR, handler::onError);
			
			download(mediaHolders, tempFiles);
			downloadSubtitles(subtitles, dest);

			if(!checkState()) return;
			ResolvedMedia output = new ResolvedMedia(media, dest, configuration);
			List<ConversionMedia> inputs = Utils.zip(mediaHolders.stream(), tempFiles.stream(), Pair::new)
				.map((p) -> new ConversionMedia(p.a.media(), p.b, Double.NaN))
				.collect(Collectors.toList());
			
			if(mediaHolders.size() == 1
					&& mediaHolders.get(0).media().format().is(configuration.outputFormat())) {
				noConversion(output, inputs.get(0));
			} else {
				double duration = inputs.stream()
					.map(ConversionMedia::path)
					.map(VideoUtils::duration)
					.max(Comparator.naturalOrder())
					.orElse(-1.0);
				
				doConversion(output, duration, inputs);
			}
			
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
		
		if(worker != null) {
			worker.stop();
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
		
		if(worker != null) {
			worker.pause();
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
		
		if(worker != null) {
			worker.resume();
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
		return size;
	}
	
	private final class DownloadEventHandler {
		
		private final DownloadTracker tracker;
		private final AtomicLong lastSize = new AtomicLong();
		
		public DownloadEventHandler(DownloadTracker tracker) {
			this.tracker = Objects.requireNonNull(tracker);
		}
		
		public void onUpdate(DownloadContext context) {
			DownloadTracker downloadTracker = (DownloadTracker) context.trackerManager().tracker();
			long current = downloadTracker.current();
			long delta = current - lastSize.get();
			
			tracker.update(delta);
			lastSize.set(current);
			
			if(size <= 0L) {
				long contextTotal = downloadTracker.total();
				
				if(contextTotal > 0L) {
					size = contextTotal;
					tracker.updateTotal(contextTotal);
				}
			}
			
			eventRegistry.call(DownloadEvent.UPDATE, SimpleDownloader.this);
		}
		
		public void onError(DownloadContext context) {
			exception = context.exception();
			state.set(TaskStates.ERROR);
			eventRegistry.call(DownloadEvent.ERROR, SimpleDownloader.this);
		}
	}
	
	private static final class MediaHolder {
		
		private final Media media;
		private long size;
		
		public MediaHolder(Media media) {
			this.media = Objects.requireNonNull(media);
			this.size = media.size();
		}
		
		public void size(long size) {
			this.size = size;
		}
		
		public Media media() {
			return media;
		}
		
		public long size() {
			return size;
		}
	}
}