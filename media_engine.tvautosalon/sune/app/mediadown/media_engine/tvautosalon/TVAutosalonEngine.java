package sune.app.mediadown.media_engine.tvautosalon;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javafx.scene.image.Image;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.VideoMedia;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Pair;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.JS;

public final class TVAutosalonEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// URLs
	private static final String URL_HOME    = "https://autosalon.tv/";
	private static final String URL_REFERER = "https://autosalon.tv/";
	
	// Selectors
	private static final String SELECTOR_PROGRAMS   = "#ms-navbar > .navbar-nav > .nav-item:first-child > .dropdown-menu > li.dropdown-header";
	private static final String SELECTOR_SEASONS    = "#main .cards-container-seasons .card-season:not(.more-link)";
	private static final String SELECTOR_EPISODES   = "#main .cards-container-episodes .card-episode-wrapper";
	private static final String SELECTOR_PAGE_ITEMS = "#main .pagination > .page-item";
	
	// Regex
	private static final Regex REGEX_SEASON = Regex.of("(?iu)^Sezóna\\s+(\\d+)$");
	
	// Allow to create an instance when registering the engine
	TVAutosalonEngine() {
	}
	
	private static final String maybeFixProgramName(String programName) {
		programName = programName.trim();
		switch(programName.toLowerCase()) {
			case "epizody autosalonu": return "Autosalon";
			case "epizody":            return "Autosalon";
			default:                   return programName;
		}
	}
	
	private static final String mediaTitle(Document document) {
		// Gather as much information as possible and construct the title
		String programName = "";
		String numSeason = "";
		String numEpisode = "";
		String episodeName = "";
		
		boolean first = true;
		for(Element elBreadcrumbItem : document.select("#body .breadcrumb .breadcrumb-item")) {
			// Skip the first item (homepage)
			if(first) {
				first = false;
				continue;
			}
			
			String text = elBreadcrumbItem.text();
			// The active item is the episode name
			if(elBreadcrumbItem.hasClass("active")) {
				episodeName = text;
			} else {
				if(programName.isEmpty()) {
					programName = text;
				} else if(numSeason.isEmpty() && REGEX_SEASON.matcher(text).matches()) {
					numSeason = text.replaceFirst("^Sezóna\\s+", "");
				}
			}
		}
		
		Regex regexNumEpisode = Regex.of("(?iu)^(\\d+)\\. epizoda$");
		for(Element elBadge : document.select("#main .article-title .badge")) {
			String text = elBadge.text();
			Matcher matcher;
			if((matcher = regexNumEpisode.matcher(text)).matches()) {
				numEpisode = matcher.group(1);
				break;
			}
		}
		
		// Fix the program name, if needed
		programName = maybeFixProgramName(programName);
		
		// Unset redundant episode name
		if(episodeName.toLowerCase().equals(programName.toLowerCase() + " " + numEpisode))
			episodeName = "";
		
		// Try to improve the episode name string (and possible extract more information)
		Regex regexEpisodeName;
		Matcher matcher;
		
		// Extract the episode number and clean episode name, if the episode name contains the program name
		regexEpisodeName = Regex.of("(?iu)^" + Regex.quote(programName) + " (\\d+): (.*)$");
		if((matcher = regexEpisodeName.matcher(episodeName)).matches()) {
			numEpisode = matcher.group(1);
			episodeName = matcher.group(2);
		}
		
		// Extract more informatio and clean episode name, if the episode name is in a specific format
		regexEpisodeName = Regex.of("(?iu)^" + Regex.quote(programName) + " (\\d+) - (?:[^,]+, )?(\\d+)\\. díl(?:, (.*))?$");
		if((matcher = regexEpisodeName.matcher(episodeName)).matches()) {
			numSeason = matcher.group(1);
			numEpisode = matcher.group(2);
			episodeName = matcher.group(3);
		}
		
		// Extract more informatio and clean episode name, if the episode name is in a specific format
		regexEpisodeName = Regex.of("(?iu)^(" + Regex.quote(programName) + " - [^,]+), (\\d+)\\. díl(?:, (.*))?$");
		if((matcher = regexEpisodeName.matcher(episodeName)).matches()) {
			programName = matcher.group(1);
			numEpisode = matcher.group(2);
			episodeName = matcher.group(3);
		}
		
		// Clean up, if some information is missing
		if(numSeason.isEmpty()) numSeason = null;
		if(numEpisode.isEmpty()) numEpisode = null;
		
		return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName, true, false, false);
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> {
			final Set<String> ignore = Set.of("/experti");
			
			Document document = HTML.from(Net.uri(URL_HOME));
			for(Element elNavItemHeader : document.select(SELECTOR_PROGRAMS)) {
				String title = elNavItemHeader.text();
				
				for(Element elItem = elNavItemHeader;
						(elItem = elItem.nextElementSibling()) != null && !elItem.hasClass("dropdown-divider");) {
					Element elItemLink = elItem.selectFirst("a");
					String href = elItemLink.attr("href");
					
					if(ignore.contains(href)) {
						continue;
					}
					
					String subTitle = elItem.text();
					if(!subTitle.equalsIgnoreCase("epizody")) {
						continue;
					}
					
					URI url = Net.uri(elItemLink.absUrl("href"));
					Program program = new Program(url, title);
					
					if(!task.add(program)) {
						return; // Do not continue
					}
				}
			}
		});
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return ListTask.of((task) -> {
			Document document = HTML.from(program.uri());
			List<Pair<URI, String>> seasons = new ArrayList<>();
			
			// Find all seasons, if any exist
			for(Element elSeason : document.select(SELECTOR_SEASONS)) {
				URI url = Net.uri(elSeason.absUrl("href"));
				String title = elSeason.selectFirst(".title").text();
				seasons.add(new Pair<>(url, title));
			}
			
			// If there are no seasons, add the current (only) page of episodes
			if(seasons.isEmpty()) {
				seasons.add(new Pair<>(program.uri(), ""));
			}
			
			String programTitle = Regex.quote(program.title());
			Regex regexSeasonInTitle = Regex.of(
				"(?iu)^" + programTitle + "\\s+(\\d+)\\s+-\\s+"
			);
			Regex regexEpisode = Regex.of(
				"(?iu)^" + programTitle + "\\s+(\\d+)$|(?:,\\s+)?(\\d+)\\.\\s+díl(?:\\s+-|,)?"
			);
			Regex regexTitle = Regex.of(
				"(?iu)^" + programTitle + "(?:\\s+\\d+\\s+-\\s+|\\s+-\\s+|,\\s+)"
			);
			
			for(Pair<URI, String> season : seasons) {
				URI baseUri = season.a;
				String seasonTitle = season.b;
				int page = 1;
				boolean hasMorePages;
				Matcher matcher;
				
				if(!baseUri.equals(program.uri())) {
					document = HTML.from(baseUri);
				}
				
				do {
					hasMorePages = false;
					
					if(page > 1) {
						document = HTML.from(Net.resolve(baseUri, baseUri.getPath() + '/' + page));
					}
					
					for(Element elEpisode : document.select(SELECTOR_EPISODES)) {
						URI url = Net.uri(elEpisode.absUrl("href"));
						String date = "";
						Element elEpisodeDate = elEpisode.selectFirst(".title > .float-right");
						
						if(elEpisodeDate != null) {
							date = elEpisodeDate.text();
							// Remove the element, so that the date does not contribute to the title
							elEpisodeDate.remove();
						}
						
						String title = elEpisode.selectFirst(".title").text();
						
						int numEpisode = 0;
						int numSeason = 0;
						
						if((matcher = REGEX_SEASON.matcher(seasonTitle)).find()) {
							numSeason = Utils.OfString.asInt(matcher.group(1));
						}
						
						if((matcher = regexSeasonInTitle.matcher(title)).find()) {
							numSeason = Utils.OfString.asInt(matcher.group(1));
							title = Utils.OfString.delete(title, matcher.start(), matcher.end());
						}
						
						if((matcher = regexTitle.matcher(title)).find()) {
							title = Utils.OfString.delete(title, matcher.start(), matcher.end());
						}
						
						if((matcher = regexEpisode.matcher(title)).find()) {
							int index = matcher.group(1) == null ? 2 : 1;
							numEpisode = Utils.OfString.asInt(matcher.group(index));
							title = Utils.OfString.delete(title, matcher.start(), matcher.end()).trim();
						}
						
						if(!date.isEmpty()) {
							title = title.isEmpty() ? date : title + " (" + date + ")";
						}
						
						Episode episode = new Episode(program, url, title, numEpisode, numSeason);
						
						if(!task.add(episode)) {
							return; // Do not continue
						}
					}
					
					Elements elPageItems = document.select(SELECTOR_PAGE_ITEMS);
					
					// Check if there is some pagination
					if(!elPageItems.isEmpty()) {
						// If the last item is disabled, there are no more pages
						hasMorePages = !elPageItems.get(elPageItems.size() - 1).hasClass("disabled");
						if(hasMorePages) ++page;
					}
				} while(hasMorePages);
			}
		});
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			Document document = HTML.from(uri);
			Element elScript = document.selectFirst("#video > script");
			String src = null;
			
			if(elScript != null) {
				src = elScript.absUrl("src");
			}
			
			// Unable to obtain the source of script that embeds the video
			if(src == null) {
				return;
			}
			
			String frameURL = null;
			String script = Web.request(Request.of(Net.uri(src)).GET()).body();
			int index;
			if((index = script.indexOf("{\"")) >= 0) {
				String configContent = Utils.bracketSubstring(script, '{', '}', false, index, script.length());
				JSONCollection config = JavaScript.readObject(configContent);
				String baseId = null;
				
				if((index = script.indexOf("var _ONNPBaseId")) >= 0) {
					baseId = Utils.unquote(JS.extractVariableContent(script, "_ONNPBaseId", index)).strip();
				}
				
				if(baseId == null) {
					return;
				}
				
				String iid = config.getString("iid");
				String mid = config.getString("mid");
				
				frameURL = Utils.format(
					"https://video.onnetwork.tv/frame86.php"
							+ "?id=ff%{base_id}s%{timestamp_ms}s1"
							+ "&iid=%{iid}s"
							+ "&e=1&lang=3&onnsfonn=1"
							+ "&mid=%{mid}s",
					"base_id", baseId,
					"timestamp_ms", System.currentTimeMillis(),
					"iid", iid,
					"mid", mid
				);
			}
			
			// Unable to obtain the frame URL "template"
			if(frameURL == null) {
				return;
			}

			// Send the request to obtain the frame's content
			HttpHeaders headers = Web.Headers.ofSingle("Referer", URL_REFERER);
			String content = Web.request(Request.of(Net.uri(frameURL)).headers(headers).GET()).body();
			
			JSONCollection playerVideos = null;
			if((index = content.indexOf("var playerVideos")) >= 0) {
				String json = Utils.bracketSubstring(content, '[', ']', false, index, content.length());
				playerVideos = JSON.read(json);
			}
			
			// Unable to obtain the player settings
			if(playerVideos == null) {
				return ;
			}
			
			String title = mediaTitle(document);
			MediaSource source = MediaSource.of(this);
			URI sourceURI = uri;
			MediaLanguage language = MediaLanguage.UNKNOWN;
			MediaMetadata metadata = MediaMetadata.builder().title(title).build();
			
			// Add all available media that are found in the player settings
			JSONCollection emptyArray = JSONCollection.emptyArray();
			for(JSONCollection video : playerVideos.collectionsIterable()) {
				JSONCollection additionalURLs = video.getCollection("urls", emptyArray);
				String videoURL = video.getString("url");
				
				List<Media> media = new ArrayList<>();
				media.addAll(MediaUtils.createMedia(source, Net.uri(videoURL), sourceURI, title, language, metadata));
				
				for(JSONCollection additionalVideo : additionalURLs.collectionsIterable()) {
					String additionalVideoURL = additionalVideo.getString("url");
					media.add(VideoMedia.simple().source(source)
					          	.uri(Net.uri(additionalVideoURL))
					          	.quality(MediaQuality.fromString(additionalVideo.getString("name"), MediaType.VIDEO))
					          	.format(MediaFormat.fromPath(additionalVideoURL))
					          	.metadata(metadata)
					          	.build());
				}
				
				for(Media m : media) {
					if(!task.add(m)) {
						return; // Do not continue
					}
				}
			}
		});
	}
	
	@Override
	public boolean isDirectMediaSupported() {
		return true;
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
		if((host.startsWith("www."))) // www prefix
			host = host.substring(4);
		if(!host.equals("autosalon.tv"))
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