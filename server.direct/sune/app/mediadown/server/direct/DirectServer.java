package sune.app.mediadown.server.direct;

import java.net.URI;
import java.util.List;
import java.util.Map;

import javafx.scene.image.Image;
import sune.app.mediadown.entity.Server;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.Utils;

public class DirectServer implements Server {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Allow to create an instance when registering the server
	DirectServer() {
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			MediaSource source = MediaSource.of(this);
			MediaMetadata metadata = MediaMetadata.empty();
			String title = Utils.OfPath.info(uri.getPath()).baseName();
			
			List<Media> media = MediaUtils.createMedia(
				source, uri, uri, title, MediaLanguage.UNKNOWN, metadata
			);
			
			for(Media s : media) {
				if(!task.add(s)) {
					return; // Do not continue
				}
			}
		});
	}
	
	@Override
	public boolean isCompatibleURI(URI uri) {
		// Never select this media getter automatically
		return false;
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