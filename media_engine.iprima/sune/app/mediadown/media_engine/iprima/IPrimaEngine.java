package sune.app.mediadown.media_engine.iprima;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javafx.scene.image.Image;
import sune.app.mediadown.Episode;
import sune.app.mediadown.Program;
import sune.app.mediadown.engine.MediaEngine;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.DefaultEpisodeObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.DefaultMediaObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.DefaultMediaObtainerNewURL;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.GraphQLProgramObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.PlayIDsMediaObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.PrimaAPIProgramObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.SnippetEpisodeObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.StaticProgramObtainer;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.util.CheckedBiFunction;
import sune.app.mediadown.util.Reflection;
import sune.app.mediadown.util.Threads;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.WorkerProxy;
import sune.app.mediadown.util.WorkerUpdatableTask;

public final class IPrimaEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	private static final IPrima[] SUPPORTED_WEBS = {
		PrimaIPrima.getInstance(),
		ZoomIPrima.getInstance(),
		LoveIPrima.getInstance(),
		CoolIPrima.getInstance(),
		ShowIPrima.getInstance(),
		StarIPrima.getInstance(),
		KrimiIPrima.getInstance(),
		MaxIPrima.getInstance(),
		CNNIPrima.getInstance(),
		PauzaIPrima.getInstance(),
	};
	
	// Allow to create an instance when registering the engine
	IPrimaEngine() {
	}
	
	private static final String subdomainOrNullIfIncompatible(String url) {
		URL urlObj = Utils.url(url);
		// Check the protocol
		String protocol = urlObj.getProtocol();
		if(!protocol.equals("http") &&
		   !protocol.equals("https"))
			return null;
		// Check the host
		String[] hostParts = urlObj.getHost().split("\\.", 2);
		if(hostParts.length < 2
				// Check only the second and top level domain names,
				// since there are many subdomains, and there may be
				// possibly even more in the future.
				|| !hostParts[1].equalsIgnoreCase("iprima.cz"))
			return null;
		// Otherwise, it is probably compatible URL, therefore return
		// the subdomain.
		return hostParts[0];
	}
	
	private static final IPrima sourceFromURL(String url) {
		String subdomain;
		if((subdomain = subdomainOrNullIfIncompatible(url)) == null)
			return null;
		
		return Stream.of(SUPPORTED_WEBS)
					.filter((w) -> w.isCompatibleSubdomain(subdomain))
					.findFirst().orElse(null);
	}
	
	// ----- Internal methods
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	private final List<Program> internal_getPrograms(WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
		List<Program> programs = new ArrayList<>();
		Set<ProgramWrapper> accumulator = new ConcurrentSkipListSet<>();
		
		CheckedBiFunction<WorkerProxy, Program, Boolean> f = ((p, program) -> {
			return (accumulator.add(new ProgramWrapper(program)) && function.apply(p, program)) || true;
		});
		
		(new ConcurrentLoop<IPrima>() {
			
			@Override
			protected void iteration(IPrima web) throws Exception {
				web.getPrograms(IPrimaEngine.this, proxy, f);
			}
		}).iterate(SUPPORTED_WEBS);
		
		accumulator.stream()
			.map(ProgramWrapper::program)
			.forEachOrdered(programs::add);
		
		return programs;
	}
	
	private final List<Episode> internal_getEpisodes(Program program, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		IPrima iprima = program.get("source");
		if(iprima == null)
			throw new IllegalStateException("Invalid program, no source found");
		return iprima.getEpisodes(this, program, proxy, function);
	}
	
	private final List<Media> internal_getMedia(String url, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
		IPrima iprima = sourceFromURL(url);
		if(iprima == null)
			throw new IllegalStateException("Cannot obtain source from the URL");
		return iprima.getMedia(this, url, proxy, function);
	}
	
	private final boolean isCompatibleSubdomain(String subdomain) {
		return Stream.of(SUPPORTED_WEBS).anyMatch((w) -> w.isCompatibleSubdomain(subdomain));
	}
	
	private final List<Program> internal_getPrograms() throws Exception {
		return internal_getPrograms(_dwp, (p, a) -> true);
	}
	
	private final List<Episode> internal_getEpisodes(Program program) throws Exception {
		return internal_getEpisodes(program, _dwp, (p, a) -> true);
	}
	
	private final List<Media> internal_getMedia(Episode episode) throws Exception {
		return internal_getMedia(episode, _dwp, (p, a) -> true);
	}
	
	private final List<Media> internal_getMedia(Episode episode, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
		return internal_getMedia(episode.uri().toString(), proxy, function);
	}
	
	private final List<Media> internal_getMedia(String url) throws Exception {
		return internal_getMedia(url, _dwp, (p, a) -> true);
	}
	
	// ----- Public methods
	
	@Override
	public List<Program> getPrograms() throws Exception {
		return internal_getPrograms();
	}
	
	@Override
	public List<Episode> getEpisodes(Program program) throws Exception {
		return internal_getEpisodes(program);
	}
	
	@Override
	public List<Media> getMedia(Episode episode) throws Exception {
		return internal_getMedia(episode);
	}
	
	@Override
	public WorkerUpdatableTask<CheckedBiFunction<WorkerProxy, Program, Boolean>, Void> getPrograms
			(CheckedBiFunction<WorkerProxy, Program, Boolean> function) {
		return WorkerUpdatableTask.voidTaskChecked(function, (p, f) -> internal_getPrograms(p, f));
	}
	
	@Override
	public WorkerUpdatableTask<CheckedBiFunction<WorkerProxy, Episode, Boolean>, Void> getEpisodes
			(Program program,
			 CheckedBiFunction<WorkerProxy, Episode, Boolean> function) {
		return WorkerUpdatableTask.voidTaskChecked(function, (p, f) -> internal_getEpisodes(program, p, f));
	}
	
	@Override
	public WorkerUpdatableTask<CheckedBiFunction<WorkerProxy, Media, Boolean>, Void> getMedia
			(Episode episode,
			 CheckedBiFunction<WorkerProxy, Media, Boolean> function) {
		return WorkerUpdatableTask.voidTaskChecked(function, (p, c) -> internal_getMedia(episode, p, c));
	}
	
	@Override
	public List<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return internal_getMedia(uri.toString());
	}
	
	@Override
	public boolean isDirectMediaSupported() {
		return true;
	}
	
	@Override
	public boolean isCompatibleURL(String url) {
		String subdomain;
		return (subdomain = subdomainOrNullIfIncompatible(url)) != null && isCompatibleSubdomain(subdomain);
	}
	
	@Override
	public String title() {
		return TITLE;
	}
	
	@Override
	public String url() {
		return URL;
	}
	
	@Override
	public String version() {
		return VERSION;
	}
	
	@Override
	public String author() {
		return AUTHOR;
	}
	
	@Override
	public Image icon() {
		return ICON;
	}
	
	@Override
	public String toString() {
		return TITLE;
	}
	
	protected static interface IPrima {
		
		List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception;
		List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception;
		List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception;
		boolean isCompatibleSubdomain(String subdomain);
	}
	
	// Context-dependant Singleton instantiator
	private static final class _Singleton {
		
		private static final Map<Class<?>, _Singleton> instances = new HashMap<>();
		
		private final Class<?> clazz;
		private Object instance;
		
		private _Singleton(Class<?> clazz) {
			this.clazz = clazz;
		}
		
		public static final <T> T getInstance() {
			Class<?> clazz = StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE).walk((stream) -> {
				return stream.filter((p) -> p.getDeclaringClass() != _Singleton.class)
						 .map(StackFrame::getDeclaringClass)
						 .findFirst().get();
			});
			return instances.computeIfAbsent(clazz, _Singleton::new).instance();
		}
		
		private final <T> T newInstance() {
			try {
				@SuppressWarnings("unchecked")
				Constructor<T> ctor = (Constructor<T>) clazz.getDeclaredConstructor();
				Reflection.setAccessible(ctor, true);
				T instance = ctor.newInstance();
				Reflection.setAccessible(ctor, false);
				return instance;
			} catch(Exception ex) {
				// Assume, the class is instantiable
			}
			// This should not happen
			return null;
		}
		
		protected final <T> T instance() {
			@SuppressWarnings("unchecked")
			T obj = (T) (instance == null ? (instance = newInstance()) : instance);
			return obj;
		}
	}
	
	private static final class PrimaIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "prima";
		
		private PrimaIPrima() {}
		public static final PrimaIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return GraphQLProgramObtainer.getInstance().getPrograms(this, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return DefaultEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return DefaultMediaObtainer.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			// Some movies are on the www subdomain that is effectively the same subdomain as iprima,
			// so we also have to handle that subdomain.
			return subdomain.equalsIgnoreCase(SUBDOMAIN) || subdomain.equalsIgnoreCase("www");
		}
	}
	
	private static final class ZoomIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "zoom";
		
		private ZoomIPrima() {}
		public static final ZoomIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return PrimaAPIProgramObtainer.getInstance().getPrograms(this, SUBDOMAIN, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return DefaultEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return DefaultMediaObtainerNewURL.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
	
	private static final class LoveIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "love";
		
		private LoveIPrima() {}
		public static final LoveIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return PrimaAPIProgramObtainer.getInstance().getPrograms(this, SUBDOMAIN, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return DefaultEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return DefaultMediaObtainer.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
	
	private static final class CoolIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "cool";
		
		private CoolIPrima() {}
		public static final CoolIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return PrimaAPIProgramObtainer.getInstance().getPrograms(this, SUBDOMAIN, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return DefaultEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return DefaultMediaObtainer.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
	
	private static final class ShowIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "show";
		
		private ShowIPrima() {}
		public static final ShowIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return PrimaAPIProgramObtainer.getInstance().getPrograms(this, SUBDOMAIN, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return DefaultEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return DefaultMediaObtainer.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
	
	private static final class StarIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "star";
		
		private StarIPrima() {}
		public static final StarIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return PrimaAPIProgramObtainer.getInstance().getPrograms(this, SUBDOMAIN, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return DefaultEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return DefaultMediaObtainer.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
	
	private static final class KrimiIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "krimi";
		
		private KrimiIPrima() {}
		public static final KrimiIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return PrimaAPIProgramObtainer.getInstance().getPrograms(this, SUBDOMAIN, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return DefaultEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return DefaultMediaObtainer.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
	
	private static final class MaxIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "max";
		
		private MaxIPrima() {}
		public static final MaxIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return PrimaAPIProgramObtainer.getInstance().getPrograms(this, SUBDOMAIN, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return DefaultEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return DefaultMediaObtainer.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
	
	private static final class CNNIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "cnn";
		private static final String URL_PROGRAMS = "https://cnn.iprima.cz/porady";
		
		private CNNIPrima() {}
		public static final CNNIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return StaticProgramObtainer.getInstance().getPrograms(this, URL_PROGRAMS, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return SnippetEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return PlayIDsMediaObtainer.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
	
	private static final class PauzaIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "pauza";
		
		private PauzaIPrima() {}
		public static final PauzaIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public List<Program> getPrograms(IPrimaEngine engine, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			return PrimaAPIProgramObtainer.getInstance().getPrograms(this, SUBDOMAIN, proxy, function);
		}
		
		@Override
		public List<Episode> getEpisodes(IPrimaEngine engine, Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			return DefaultEpisodeObtainer.getInstance().getEpisodes(program, proxy, function);
		}
		
		@Override
		public List<Media> getMedia(IPrimaEngine engine, String url, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
			return DefaultMediaObtainer.getInstance().getMedia(url, proxy, function, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
	
	private static final class ProgramWrapper implements Comparable<ProgramWrapper> {
		
		private final Program program;
		
		public ProgramWrapper(Program program) {
			this.program = Objects.requireNonNull(program);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(program);
		}
		
		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(obj == null)
				return false;
			if(getClass() != obj.getClass())
				return false;
			ProgramWrapper other = (ProgramWrapper) obj;
			// Do not compare program data
			return Objects.equals(program.uri(), other.program.uri())
						&& Objects.equals(program.title().toLowerCase(), other.program.title().toLowerCase());
		}
		
		@Override
		public int compareTo(ProgramWrapper w) {
			if(Objects.requireNonNull(w) == this) return 0;
			return Comparator.<ProgramWrapper>nullsLast((a, b) ->
							IPrimaHelper.compareNatural(a.program.title().toLowerCase(),
							                            b.program.title().toLowerCase()))
						.thenComparing((e) -> e.program.uri())
						.compare(this, w);
		}
		
		public Program program() {
			return program;
		}
	}
	
	private static abstract class ConcurrentLoop<T> {
		
		protected final ExecutorService executor = Threads.Pools.newWorkStealing();
		
		protected abstract void iteration(T value) throws Exception;
		
		protected final void await() throws Exception {
			executor.shutdown();
			// Do not throw the InterruptedException (i.e. when cancelled the loop, etc.)
			Utils.ignore(() -> executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS));
		}
		
		protected final void submit(T value) {
			executor.submit(Utils.callable(() -> iteration(value)));
		}
		
		@SuppressWarnings("unchecked")
		public void iterate(T... values) throws Exception {
			for(T value : values)
				submit(value);
			await();
		}
	}
}