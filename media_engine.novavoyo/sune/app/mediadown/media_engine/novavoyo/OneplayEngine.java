package sune.app.mediadown.media_engine.novavoyo;

import java.net.URI;
import java.util.Map;

import javafx.scene.image.Image;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;

public final class OneplayEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Allow to create an instance when registering the engine
	OneplayEngine() {
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return Oneplay.instance().getPrograms();
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return Oneplay.instance().getEpisodes(program);
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return Oneplay.instance().getMedia(this, uri, data);
	}
	
	@Override
	public boolean isDirectMediaSupported() {
		return true;
	}
	
	@Override
	public boolean isCompatibleURI(URI uri) {
		String protocol = uri.getScheme();
		
		if(!protocol.equals("http") && !protocol.equals("https")) {
			return false;
		}
		
		String host = uri.getHost();
		
		if(host.startsWith("www.")) {
			host = host.substring(4);
		}
		
		if(!host.equals("oneplay.cz")) {
			return false;
		}
		
		return true;
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
}
