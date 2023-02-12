package sune.app.mediadown.server.html5;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javafx.scene.image.Image;
import sune.app.mediadown.Shared;
import sune.app.mediadown.concurrent.ListTask;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.VideoMedia;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.server.Server;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Web.StringResponse;

public class HTML5Server implements Server {
	
	// TODO: Add obtaining of subtitles tracks
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Allow to create an instance when registering the server
	HTML5Server() {
	}
	
	private final Document getDocument(String url, Map<String, Object> data) throws Exception {
		Map<String, String> headers = new LinkedHashMap<>();
		data.forEach((k, v) -> headers.put(k, v != null ? v.toString() : ""));
		StringResponse response = Web.request(new GetRequest(Utils.url(url), Shared.USER_AGENT, headers));
		return Utils.parseDocument(response.content, url);
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			MediaSource source = MediaSource.of(this);
			MediaMetadata metadata = MediaMetadata.builder().sourceURI(uri).build();
			Document document = getDocument(uri.toString(), data);
			Elements videos = document.getElementsByTag("video");
			
			for(Element video : videos) {
				if(video.hasAttr("src")) {
					String sourceURL = Utils.urlFix(video.absUrl("src"));
					Media media = VideoMedia.simple().source(source)
							.uri(Utils.uri(sourceURL)).format(MediaFormat.fromPath(sourceURL))
							.quality(MediaQuality.UNKNOWN).metadata(metadata)
							.build();
					
					if(!task.add(media)) {
						return; // Do not continue
					}
				}
				
				Elements sourceTags = video.getElementsByTag("source");
				for(Element sourceTag : sourceTags) {
					String sourceURL  = Utils.urlFix(sourceTag.absUrl("src"));
					String sourceType = sourceTag.attr("type");
					Media media = VideoMedia.simple().source(source)
							.uri(Utils.uri(sourceURL)).format(MediaFormat.fromMimeType(sourceType))
							.quality(MediaQuality.UNKNOWN).metadata(metadata)
							.build();
					
					if(!task.add(media)) {
						return; // Do not continue
					}
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