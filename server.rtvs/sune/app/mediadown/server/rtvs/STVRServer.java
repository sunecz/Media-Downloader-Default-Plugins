package sune.app.mediadown.server.rtvs;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.entity.Server;
import sune.app.mediadown.media.AudioMedia;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

public class STVRServer implements Server {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	private static final Regex REGEX_URI = Regex.of(
		"https?://(?:www\\.)?stvr\\.sk/((?:radio|televizia)/archiv|deti/(?:rozhlas|televizia))/\\d+/\\d+/?"
	);
	
	// Allow to create an instance when registering the server
	STVRServer() {
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			Document document = HTML.from(uri);
			Matcher matcher = REGEX_URI.matcher(uri.toString());
			
			if(!matcher.matches()) {
				throw new IllegalStateException("Unsupported URL");
			}
			
			String mediaType, endpoint, property;
			switch(matcher.group(1)) {
				case "televizia/archiv":
				case "deti/televizia": {
					mediaType = "video";
					endpoint = "archive5f";
					property = "clip";
					break;
				}
				case "radio/archiv":
				case "deti/rozhlas": {
					mediaType = "audio";
					endpoint = "audio5f";
					property = "playlist.0";
					break;
				}
				default: {
					throw new IllegalStateException("Unsupported URL");
				}
			}
			
			Element iframe = document.selectFirst("iframe[id^='player_" + mediaType + "_']");
			
			if(iframe == null) {
				throw new IllegalStateException("Cannot find the iframe");
			}
			
			int id = Integer.valueOf(Utils.afterLast(iframe.attr("id"), "_"));
			String playlistUrl = Utils.format(
				"https://www.stvr.sk/json/%{endpoint}s.json?id=%{id}d&=&b=chrome&p=win&f=0&d=1",
				"endpoint", endpoint,
				"id", id
			);
			
			JSONCollection playlist;
			try(Response.OfStream response = Web.requestStream(Request.of(Net.uri(playlistUrl)).GET())) {
				playlist = JSON.read(response.stream()).getCollection(property);
			}
			
			String title = Utils.OfPath.fileName(playlist.getString("title"));
			MediaSource source = MediaSource.of(this);
			MediaMetadata metadata = MediaMetadata.builder().sourceURI(uri).title(title).build();
			
			switch(mediaType) {
				case "audio": {
					for(JSONCollection itemSource : playlist.getCollection("sources").collectionsIterable()) {
						String formatString = itemSource.getString("type");
						MediaFormat sourceFormat = (
							// Temporary fix till the next application's version
							formatString.equalsIgnoreCase("audio/mp3")
								? MediaFormat.MP3
								: MediaFormat.fromMimeType(formatString)
						);
						URI sourceUri = Net.uri(itemSource.getString("src"));
						
						Media media = AudioMedia.simple()
							.source(source)
							.uri(sourceUri).format(sourceFormat).quality(MediaQuality.UNKNOWN)
							.metadata(metadata)
							.build();
						
						if(!task.add(media)) {
							return; // Do not continue
						}
					}
					
					break;
				}
				case "video":
				default: {
					for(JSONCollection itemSource : playlist.getCollection("sources").collectionsIterable()) {
						MediaFormat sourceFormat = MediaFormat.fromMimeType(itemSource.getString("type"));
						URI sourceUri = Net.uri(itemSource.getString("src"));
						
						if(sourceFormat.is(MediaFormat.DASH)) {
							// DASH timeouts for some reason, however, there should always be M3U8 available,
							// so just ignore it.
							continue;
						}
						
						List<Media> media = MediaUtils.createMedia(
							source, sourceUri, uri, title, MediaLanguage.UNKNOWN, metadata
						);
						
						for(Media m : media) {
							if(!task.add(m)) {
								return; // Do not continue
							}
						}
					}
					
					break;
				}
			}
		});
	}
	
	@Override
	public boolean isCompatibleURI(URI uri) {
		// Check the protocol
		String protocol = uri.getScheme();
		if(!protocol.equals("http") &&
		   !protocol.equals("https"))
			return false;
		// Check the host
		String host = uri.getHost();
		if(host.startsWith("www.")) // www prefix
			host = host.substring(4);
		if(!host.equals("stvr.sk"))
			return false;
		// Otherwise, it is probably compatible URL
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