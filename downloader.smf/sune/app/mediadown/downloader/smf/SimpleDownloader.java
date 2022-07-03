package sune.app.mediadown.downloader.smf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sune.app.mediadown.Download;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.Shared;
import sune.app.mediadown.convert.ConversionConfiguration;
import sune.app.mediadown.download.AcceleratedDownloader;
import sune.app.mediadown.download.DownloadConfiguration;
import sune.app.mediadown.download.IInternalDownloader;
import sune.app.mediadown.download.IInternalListener;
import sune.app.mediadown.download.MediaDownloadConfiguration;
import sune.app.mediadown.download.SingleFileDownloader;
import sune.app.mediadown.event.DownloadEvent;
import sune.app.mediadown.event.EventRegistry;
import sune.app.mediadown.event.EventType;
import sune.app.mediadown.event.Listener;
import sune.app.mediadown.event.tracker.DownloadTracker;
import sune.app.mediadown.event.tracker.PlainTextTracker;
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
import sune.app.mediadown.util.EventUtils;
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

public final class SimpleDownloader implements Download {
	
	private static final Map<String, String> HEADERS = Web.headers("Accept=*/*");
	
	private final Translation translation = MediaDownloader.translation().getTranslation("plugin.downloader.smf");
	private final TrackerManager manager = new TrackerManager();
	private final EventRegistry<DownloadEvent> eventRegistry = new EventRegistry<>();
	
	private final Media media;
	private final Path dest;
	private final MediaDownloadConfiguration configuration;
	
	private final AtomicBoolean running = new AtomicBoolean();
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean done = new AtomicBoolean();
	private final AtomicBoolean paused = new AtomicBoolean();
	private final AtomicBoolean stopped = new AtomicBoolean();
	private final SyncObject lockPause = new SyncObject();
	
	private Worker worker;
	private long size = MediaConstants.UNKNOWN_SIZE;
	private DownloadPipelineResult pipelineResult;
	private IInternalDownloader downloader;
	
	SimpleDownloader(Media media, Path dest, MediaDownloadConfiguration configuration) {
		this.media         = Objects.requireNonNull(media);
		this.dest          = Objects.requireNonNull(dest);
		this.configuration = Objects.requireNonNull(configuration);
		manager.setTracker(new WaitTracker());
	}
	
	private static final int compareFirstLongestString(String a, String b) {
		int cmp; return (cmp = Integer.compare(b.length(), a.length())) == 0 ? 1 : cmp;
	}
	
	private static final IInternalDownloader createDownloader(TrackerManager manager, DownloadConfiguration configuration) {
		return (configuration.isSingleRequest()
					? new SingleFileDownloader(manager)
					: (configuration.isAccelerated()
							? new AcceleratedDownloader(manager)
							: new AcceleratedDownloader(manager, 1)
					  )
			   );
	}
	
	private final boolean checkIfCanContinue() {
		// Check if paused
		if(isPaused()) lockPause.await();
		// Check if running
		return running.get();
	}
	
	private final boolean computeTotalSize(List<MediaHolder> mediaHolders, List<MediaHolder> subtitles) {
		if(!MediaDownloader.configuration().computeStreamSize())
			return false;
		String text = translation.getSingle("progress.compute_total_size");
		PlainTextTracker tracker = new PlainTextTracker();
		manager.setTracker(tracker);
		tracker.setText(text);
		tracker.setProgress(0.0);
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
					tracker.setProgress(counter.incrementAndGet() / count);
				} else {
					worker.submit(() -> {
						HeadRequest request = new HeadRequest(Utils.url(mh.media().uri()), Shared.USER_AGENT, HEADERS);
						long size = Utils.ignore(() -> Web.size(request), MediaConstants.UNKNOWN_SIZE);
						mh.size(size);
						if(size > 0L) theSize.getAndAdd(size);
						tracker.setProgress(counter.incrementAndGet() / count);
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
		if(running.get()) return; // Nothing to do
		running.set(true);
		started.set(true);
		eventRegistry.call(DownloadEvent.BEGIN, this);
		manager.setUpdateListener(() -> eventRegistry.call(DownloadEvent.UPDATE, new Pair<>(this, manager)));
		Utils.ignore(() -> NIO.createFile(dest));
		List<MediaHolder> mediaHolders = MediaUtils.solids(media).stream()
				.filter((m) -> m.type().is(MediaType.VIDEO) || m.type().is(MediaType.AUDIO))
				.map(MediaHolder::new)
				.collect(Collectors.toList());
		List<MediaHolder> subtitles = configuration.selectedMedia(MediaType.SUBTITLES).stream()
				.map(MediaHolder::new)
				.collect(Collectors.toList());
		boolean sizeComputed = computeTotalSize(mediaHolders, subtitles);
		if(!checkIfCanContinue()) return;
		DownloadTracker tracker = new DownloadTracker(size, sizeComputed);
		manager.setTracker(tracker);
		List<Path> tempFiles = new ArrayList<>(mediaHolders.size());
		try {
			String fileNameNoType = Utils.fileNameNoType(dest.getFileName().toString());
			for(int i = 0, l = mediaHolders.size(); i < l; ++i) {
				Path tempFile = dest.getParent().resolve(fileNameNoType + "." + i + ".part");
				Utils.ignore(() -> NIO.deleteFile(tempFile));
				tempFiles.add(tempFile);
			}
			TrackerManager dummyManager = new TrackerManager();
			downloader = createDownloader(dummyManager, DownloadConfiguration.getDefault());
			downloader.setTracker(new DownloadTracker(-1L, false));
			SD_IInternalListener internalListener = new SD_IInternalListener(tracker);
			EventUtils.mapListeners(DownloadEvent.class, downloader, internalListener.toEventMapper());
			dummyManager.setUpdateListener(() -> downloader.call(DownloadEvent.UPDATE, new Pair<>(this, dummyManager)));
			Iterator<Path> tempFileIt = tempFiles.iterator();
			for(MediaHolder mh : mediaHolders) {
				if(!checkIfCanContinue())
					break;
				Path tempFile = tempFileIt.next();
				GetRequest request = new GetRequest(Utils.url(mh.media().uri()), Shared.USER_AGENT, HEADERS);
				downloader.start(request, tempFile, this, mh.size());
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
					downloader.start(request, subDest, this, subtitle.size());
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
		if(worker != null)
			worker.interrupt();
		if(!done.get())
			stopped.set(true);
		eventRegistry.call(DownloadEvent.END, this);
	}
	
	@Override
	public final void pause() throws Exception {
		if(paused.get()) return; // Nothing to do
		if(downloader != null)
			downloader.pause();
		if(worker != null)
			worker.pause();
		running.set(false);
		paused .set(true);
		eventRegistry.call(DownloadEvent.PAUSE, this);
	}
	
	@Override
	public final void resume() throws Exception {
		if(!paused.get()) return; // Nothing to do
		if(downloader != null)
			downloader.resume();
		if(worker != null)
			worker.resume();
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
		private long lastSize;
		
		public SD_IInternalListener(DownloadTracker tracker) {
			this.tracker = tracker;
		}
		
		@Override
		public <E> Listener<E> beginListener() {
			return ((o) -> {
				lastSize = 0L;
			});
		}
		
		@Override
		public <E> Listener<E> updateListener() {
			return ((o) -> {
				@SuppressWarnings("unchecked")
				Pair<Download, TrackerManager> pair = (Pair<Download, TrackerManager>) o;
				DownloadTracker downloadTracker = (DownloadTracker) pair.b.getTracker();
				long current = downloadTracker.getCurrent();
				long delta   = current - lastSize;
				lastSize     = current;
				tracker.update(delta);
			});
		}
		
		@Override
		public <E> Listener<E> endListener() {
			return ((o) -> {});
		}
		
		@Override
		public <E> Listener<E> errorListener() {
			return ((o) -> {
				@SuppressWarnings("unchecked")
				Pair<Download, Exception> pair = (Pair<Download, Exception>) o;
				eventRegistry.call(DownloadEvent.ERROR, pair);
			});
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