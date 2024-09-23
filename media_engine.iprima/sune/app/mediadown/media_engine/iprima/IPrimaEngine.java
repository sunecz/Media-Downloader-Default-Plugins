package sune.app.mediadown.media_engine.iprima;

import java.net.URI;
import java.util.Map;
import java.util.stream.Stream;

import javafx.scene.image.Image;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.ConcurrentLoop;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.DefaultMediaObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.SnippetEpisodeObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.SnippetProgramObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.StaticEpisodeObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.StaticProgramObtainer;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper._Singleton;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;

public final class IPrimaEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	private static final IPrima[] SUPPORTED_WEBS = {
		PrimaPlus.getInstance(),
		ZoomIPrima.getInstance(),
		CNNIPrima.getInstance(),
	};
	
	// Allow to create an instance when registering the engine
	IPrimaEngine() {
	}
	
	private static final String subdomainOrNullIfIncompatible(URI uri) {
		// Check the protocol
		String protocol = uri.getScheme();
		if(!protocol.equals("http") &&
		   !protocol.equals("https"))
			return null;
		// Check the host
		String[] hostParts = uri.getHost().split("\\.", 2);
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
	
	private static final IPrima sourceFromURL(URI uri) {
		String subdomain;
		if((subdomain = subdomainOrNullIfIncompatible(uri)) == null) {
			return null;
		}
		
		return Stream.of(SUPPORTED_WEBS)
					.filter((w) -> w.isCompatibleSubdomain(subdomain))
					.findFirst().orElse(null);
	}
	
	private final boolean isCompatibleSubdomain(String subdomain) {
		return subdomain != null
					&& Stream.of(SUPPORTED_WEBS)
					         .anyMatch((w) -> w.isCompatibleSubdomain(subdomain));
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> {
			(new ConcurrentLoop<IPrima>() {
				
				@Override
				protected void iteration(IPrima web) throws Exception {
					ListTask<Program> webTask = web.getPrograms();
					webTask.forwardAdd(task);
					webTask.startAndWait();
				}
			}).iterate(SUPPORTED_WEBS);
		});
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		IPrima iprima = sourceFromURL(program.uri());
		
		if(iprima == null) {
			throw new IllegalStateException("Cannot obtain source from the URL");
		}
		
		return iprima.getEpisodes(program);
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		IPrima iprima = sourceFromURL(uri);
		
		if(iprima == null) {
			throw new IllegalStateException("Cannot obtain source from the URL");
		}
		
		return iprima.getMedia(this, uri);
	}
	
	@Override
	public boolean isDirectMediaSupported() {
		return true;
	}
	
	@Override
	public boolean isCompatibleURI(URI uri) {
		return isCompatibleSubdomain(subdomainOrNullIfIncompatible(uri));
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
		
		ListTask<Program> getPrograms() throws Exception;
		ListTask<Episode> getEpisodes(Program program) throws Exception;
		ListTask<Media> getMedia(IPrimaEngine engine, URI uri) throws Exception;
		boolean isCompatibleSubdomain(String subdomain);
	}
	
	private static final class ZoomIPrima implements IPrima {
		
		private static final String SUBDOMAIN = "zoom";
		
		private ZoomIPrima() {}
		public static final ZoomIPrima getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public ListTask<Program> getPrograms() throws Exception {
			return SnippetProgramObtainer.getPrograms(SUBDOMAIN);
		}
		
		@Override
		public ListTask<Episode> getEpisodes(Program program) throws Exception {
			return StaticEpisodeObtainer.getEpisodes(program);
		}
		
		@Override
		public ListTask<Media> getMedia(IPrimaEngine engine, URI uri) throws Exception {
			return DefaultMediaObtainer.getMedia(uri, engine);
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
		public ListTask<Program> getPrograms() throws Exception {
			return StaticProgramObtainer.getPrograms(URL_PROGRAMS);
		}
		
		@Override
		public ListTask<Episode> getEpisodes(Program program) throws Exception {
			return SnippetEpisodeObtainer.getEpisodes(program);
		}
		
		@Override
		public ListTask<Media> getMedia(IPrimaEngine engine, URI uri) throws Exception {
			return DefaultMediaObtainer.getMedia(uri, engine);
		}
		
		@Override
		public boolean isCompatibleSubdomain(String subdomain) {
			return subdomain.equalsIgnoreCase(SUBDOMAIN);
		}
	}
}