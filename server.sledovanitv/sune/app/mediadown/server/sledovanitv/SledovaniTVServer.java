package sune.app.mediadown.server.sledovanitv;

import java.net.URI;
import java.util.Map;

import javafx.scene.image.Image;
import sune.app.mediadown.entity.Server;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;

public class SledovaniTVServer implements Server {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Allow to create an instance when registering the server
	SledovaniTVServer() {
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return SledovaniTV.instance().getMedia(MediaSource.of(this), uri);
	}
	
	@Override
	public boolean isCompatibleURI(URI uri) {
		return SledovaniTV.instance().isCompatibleUri(uri);
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
