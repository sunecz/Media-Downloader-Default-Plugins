package sune.app.mediadown.downloader.smf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sune.app.mediadown.Download;
import sune.app.mediadown.InternalState;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.Shared;
import sune.app.mediadown.TaskStates;
import sune.app.mediadown.convert.ConversionConfiguration;
import sune.app.mediadown.download.AcceleratedFileDownloader;
import sune.app.mediadown.download.DownloadConfiguration;
import sune.app.mediadown.download.DownloadResult;
import sune.app.mediadown.download.InternalDownloader;
import sune.app.mediadown.download.MediaDownloadConfiguration;
import sune.app.mediadown.event.DownloadEvent;
import sune.app.mediadown.event.Event;
import sune.app.mediadown.event.EventRegistry;
import sune.app.mediadown.event.Listener;
import sune.app.mediadown.event.tracker.DownloadTracker;
import sune.app.mediadown.event.tracker.PlainTextTracker;
import sune.app.mediadown.event.tracker.TrackerEvent;
import sune.app.mediadown.event.tracker.TrackerManager;
import sune.app.mediadown.event.tracker.WaitTracker;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.pipeline.DownloadPipelineResult;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Pair;
import sune.app.mediadown.util.SyncObject;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.VideoUtils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Web.HeadRequest;
import sune.app.mediadown.util.Worker;

public final class SimpleDownloader implements Download, DownloadResult {
	
	private static final Map<String, String> HEADERS = Web.headers("Accept=*/*");
	
	private final Translation translation = MediaDownloader.translation().getTranslation("plugin.downloader.smf");
	private final TrackerManager manager = new TrackerManager();
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
	
	SimpleDownloader(Media media, Path dest, MediaDownloadConfiguration configuration) {
		this.media         = Objects.requireNonNull(media);
		this.dest          = Objects.requireNonNull(dest);
		this.configuration = Objects.requireNonNull(configuration);
		manager.tracker(new WaitTracker());
	}
	
	private static final int compareFirstLongestString(String a, String b) {
		int cmp; return (cmp = Integer.compare(b.length(), a.length())) == 0 ? 1 : cmp;
	}
	
	private static final InternalDownloader createDownloader(TrackerManager manager) {
		return new AcceleratedFileDownloader(manager);
	}
	
	private final boolean checkIfCanContinue() {
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
		manager.tracker(tracker);
		tracker.text(text);
		tracker.progress(0.0);
		worker = Worker.createThreadedWorker();
		size = 0L; // Reset the size
		double count = mediaHolders.size() + subtitles.size();
		AtomicInteger counter = new AtomicInteger();
		AtomicLong theSize = new AtomicLong();
		try {
			for(MediaHolder mh : Utils.iterable(Stream.concat(mediaHolders.stream(), subtitles.stream()).iterator())) {
				if(!checkIfCanContinue()) {
					// Important to interrupt before break
					worker.interrupt();
					// Exit the loop
					break;
				}
				long mediaSize = mh.size();
				if(mediaSize > 0L) {
					theSize.getAndAdd(mediaSize);
					tracker.progress(counter.incrementAndGet() / count);
				} else {
					worker.submit(() -> {
						HeadRequest request = new HeadRequest(Utils.url(mh.media().uri()), Shared.USER_AGENT, HEADERS);
						long size = Utils.ignore(() -> Web.size(request), MediaConstants.UNKNOWN_SIZE);
						mh.size(size);
						if(size > 0L) theSize.getAndAdd(size);
						tracker.progress(counter.incrementAndGet() / count);
					});
				}
			}
			worker.waitTillDone();
			size = theSize.get();
			return true;
		} finally {
			worker.interrupt();
			worker = null;
		}
	}
	
	private final void noConversion(Path dest, Path input) throws IOException {
		NIO.move_force(input, dest);
		pipelineResult = DownloadPipelineResult.noConversion();
	}
	
	private final void doConversion(Path dest, Path... inputs) {
		double duration = List.of(inputs).stream().map(VideoUtils::duration).max(Comparator.naturalOrder()).orElse(-1.0);
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
		
		List<MediaHolder> mediaHolders = MediaUtils.solids(media).stream()
				.filter((m) -> m.type().is(MediaType.VIDEO) || m.type().is(MediaType.AUDIO))
				.map(MediaHolder::new)
				.collect(Collectors.toList());
		List<MediaHolder> subtitles = configuration.selectedMedia(MediaType.SUBTITLES).stream()
				.map(MediaHolder::new)
				.collect(Collectors.toList());
		
		DownloadTracker localTracker = new DownloadTracker();
		downloader.setTracker(localTracker);
		
		computeTotalSize(mediaHolders, subtitles);
		if(!checkIfCanContinue()) return;
		
		downloadTracker = new DownloadTracker(size);
		manager.tracker(downloadTracker);
		List<Path> tempFiles = new ArrayList<>(mediaHolders.size());
		try {
			String fileNameNoType = Utils.fileNameNoType(dest.getFileName().toString());
			for(int i = 0, l = mediaHolders.size(); i < l; ++i) {
				Path tempFile = dest.getParent().resolve(fileNameNoType + "." + i + ".part");
				Utils.ignore(() -> NIO.deleteFile(tempFile));
				tempFiles.add(tempFile);
			}
			
			DownloadEventHandler handler = new DownloadEventHandler(downloadTracker);
			downloader.addEventListener(DownloadEvent.BEGIN, handler::onBegin);
			downloader.addEventListener(DownloadEvent.UPDATE, handler::onUpdate);
			downloader.addEventListener(DownloadEvent.ERROR, handler::onError);
			
			Iterator<Path> tempFileIt = tempFiles.iterator();
			for(MediaHolder mh : mediaHolders) {
				if(!checkIfCanContinue()) break;
				Path tempFile = tempFileIt.next();
				GetRequest request = new GetRequest(Utils.url(mh.media().uri()), Shared.USER_AGENT, HEADERS);
				downloader.start(request, tempFile, new DownloadConfiguration(mh.size()));
			}
			
			if(!checkIfCanContinue()) return;
			// Download subtitles, if any
			if(!subtitles.isEmpty()) {
				Path subtitlesDir = dest.getParent();
				String subtitlesFileName = Utils.fileNameNoType(dest.getFileName().toString());
				for(MediaHolder subtitle : subtitles) {
					if(!checkIfCanContinue()) break;
					SubtitlesMedia sm = (SubtitlesMedia) subtitle.media();
					String subtitleType = Opt.of(sm.format().fileExtensions())
							.ifFalse(List::isEmpty).map((l) -> l.get(0))
							.orElseGet(() -> Utils.fileType(sm.uri().toString()));
					String subtitleLanguage = sm.language().codes().stream()
							.sorted(SimpleDownloader::compareFirstLongestString)
							.findFirst().orElse(null);
					String subtitleFileName = subtitlesFileName
							+ (subtitleLanguage != null ? '.' + subtitleLanguage : "")
							+ (!subtitleType.isEmpty() ? '.' + subtitleType : "");
					Path subDest = subtitlesDir.resolve(subtitleFileName);
					GetRequest request = new GetRequest(Utils.url(sm.uri()), Shared.USER_AGENT, HEADERS);
					downloader.start(request, subDest, new DownloadConfiguration(subtitle.size()));
				}
			}
			
			if(!checkIfCanContinue()) return;
			MediaFormat outFormat = MediaFormat.fromPath(dest);
			if(mediaHolders.size() == 1) {
				MediaHolder mh = mediaHolders.get(0);
				Path tempFile = tempFiles.get(0);
				if(mh.media().format().is(outFormat)) noConversion(dest, tempFile);
				else doConversion(dest, tempFile);
			} else {
				doConversion(dest, Utils.toArray(tempFiles, Path.class));
			}
			
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
		
		if(worker != null) {
			worker.interrupt();
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
		
		if(worker != null) {
			worker.pause();
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
		
		if(worker != null) {
			worker.resume();
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
		private long lastSize;
		
		public DownloadEventHandler(DownloadTracker tracker) {
			this.tracker = Objects.requireNonNull(tracker);
		}
		
		public void onBegin(InternalDownloader downloader) {
			lastSize = 0L;
		}
		
		public void onUpdate(Pair<InternalDownloader, TrackerManager> pair) {
			DownloadTracker downloadTracker = (DownloadTracker) pair.b.tracker();
			long current = downloadTracker.current();
			long delta = current - lastSize;
			
			tracker.update(delta);
			lastSize = current;
		}
		
		public void onError(Pair<InternalDownloader, Exception> pair) {
			eventRegistry.call(DownloadEvent.ERROR, pair);
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