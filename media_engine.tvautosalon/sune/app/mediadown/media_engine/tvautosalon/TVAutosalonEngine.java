package sune.app.mediadown.media_engine.tvautosalon;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javafx.scene.image.Image;
import sune.app.mediadown.Episode;
import sune.app.mediadown.Program;
import sune.app.mediadown.Shared;
import sune.app.mediadown.engine.MediaEngine;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.VideoMedia;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.util.CheckedBiFunction;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.Pair;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.JS;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.WorkerProxy;
import sune.app.mediadown.util.WorkerUpdatableTask;
import sune.util.ssdf2.SSDCollection;

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
				} else if(numSeason.isEmpty() && text.matches("^Sezóna\\s+\\d+$")) {
					numSeason = text.replaceFirst("^Sezóna\\s+", "");
				}
			}
		}
		
		Pattern regexNumEpisode = Pattern.compile("^(\\d+)\\. epizoda$");
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
		Pattern regexEpisodeName;
		Matcher matcher;
		
		// Extract the episode number and clean episode name, if the episode name contains the program name
		regexEpisodeName = Pattern.compile("(?i)^" + Pattern.quote(programName) + " (\\d+): (.*)$");
		if((matcher = regexEpisodeName.matcher(episodeName)).matches()) {
			numEpisode = matcher.group(1);
			episodeName = matcher.group(2);
		}
		
		// Extract more informatio and clean episode name, if the episode name is in a specific format
		regexEpisodeName = Pattern.compile("(?i)^" + Pattern.quote(programName) + " (\\d+) - (?:[^,]+, )?(\\d+)\\. díl(?:, (.*))?$");
		if((matcher = regexEpisodeName.matcher(episodeName)).matches()) {
			numSeason = matcher.group(1);
			numEpisode = matcher.group(2);
			episodeName = matcher.group(3);
		}
		
		// Extract more informatio and clean episode name, if the episode name is in a specific format
		regexEpisodeName = Pattern.compile("(?i)^(" + Pattern.quote(programName) + " - [^,]+), (\\d+)\\. díl(?:, (.*))?$");
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
	
	// ----- Internal methods
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	private final List<Program> internal_getPrograms() throws Exception {
		return internal_getPrograms(_dwp, (p, a) -> true);
	}
	
	private final List<Program> internal_getPrograms(WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
		List<Program> programs = new ArrayList<>();
		final Set<String> ignore = Set.of("/experti");
		
		Document document = Utils.document(URL_HOME);
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
				
				String url = elItemLink.absUrl("href");
				Program program = new Program(Utils.uri(url), title);
				
				programs.add(program);
				if(!function.apply(proxy, program)) {
					return null; // Do not continue
				}
			}
		}
		
		return programs;
	}
	
	private final List<Episode> internal_getEpisodes(Program program) throws Exception {
		return internal_getEpisodes(program, _dwp, (p, a) -> true);
	}
	
	private final List<Episode> internal_getEpisodes(Program program, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		List<Episode> episodes = new ArrayList<>();
		
		Document document = Utils.document(program.uri());
		List<Pair<URI, String>> seasons = new ArrayList<>();
		
		// Find all seasons, if any exist
		for(Element elSeason : document.select(SELECTOR_SEASONS)) {
			String url = elSeason.absUrl("href");
			String title = elSeason.selectFirst(".title").text();
			seasons.add(new Pair<>(Utils.uri(url), title));
		}
		
		// If there are no seasons, add the current (only) page of episodes
		if(seasons.isEmpty()) {
			seasons.add(new Pair<>(null, null));
		}
		
		for(Pair<URI, String> season : seasons) {
			URI baseURI = season.a != null ? season.a : program.uri();
			String seasonTitle = season.b != null ? season.b + " - " : "";
			Document doc = season.a != null ? Utils.document(season.a) : document;
			boolean hasMorePages;
			int page = 1;
			
			do {
				hasMorePages = false;
				
				if(page > 1) {
					doc = Utils.document(baseURI.resolve(baseURI.getPath() + '/' + page));
				}
				
				for(Element elEpisode : doc.select(SELECTOR_EPISODES)) {
					String url = elEpisode.absUrl("href");
					
					String date = "";
					Element elEpisodeDate = elEpisode.selectFirst(".title > .float-right");
					if(elEpisodeDate != null) {
						date = " (" + elEpisodeDate.text() + ")";
						// Remove the element, so that the date does not contribute to the title
						elEpisodeDate.remove();
					}
					
					String title = elEpisode.selectFirst(".title").text();
					title = String.format("%s%s%s", seasonTitle, title, date);
					Episode episode = new Episode(program, Utils.uri(url), title);
					
					episodes.add(episode);
					if(!function.apply(proxy, episode))
						return null; // Do not continue
				}
				
				// Check if there is some pagination
				Elements elPageItems = doc.select(SELECTOR_PAGE_ITEMS);
				if(!elPageItems.isEmpty()) {
					// If the last item is disabled, there are no more pages
					hasMorePages = !elPageItems.get(elPageItems.size() - 1).hasClass("disabled");
					if(hasMorePages) ++page;
				}
			} while(hasMorePages);
		}
		
		return episodes;
	}
	
	private final List<Media> internal_getMedia(String url) throws Exception {
		return internal_getMedia(url, _dwp, (p, a) -> true);
	}
	
	private final List<Media> internal_getMedia(String url, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
		List<Media> sources = new ArrayList<>();
		
		Document document = Utils.document(url);
		Element elScript = document.selectFirst("#video > script");
		String src = null;
		
		if(elScript != null) {
			src = elScript.absUrl("src");
		}
		
		// Unable to obtain the source of script that embeds the video
		if(src == null) {
			return null;
		}
		
		String frameURL = null;
		String script = Web.request(new GetRequest(Utils.url(src), Shared.USER_AGENT)).content;
		int index;
		if((index = script.indexOf("{\"")) >= 0) {
			String configContent = Utils.bracketSubstring(script, '{', '}', false, index, script.length());
			SSDCollection config = JSON.read(configContent);
			String baseId = null;
			
			if((index = script.indexOf("var _ONNPBaseId")) >= 0) {
				baseId = Utils.unquote(JS.extractVariableContent(script, "_ONNPBaseId", index)).strip();
			}
			
			if(baseId == null) {
				return null;
			}
			
			String iid = config.getDirectString("iid");
			String mid = config.getDirectString("mid");
			
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
			return null;
		}

		// Send the request to obtain the frame's content
		Map<String, String> headers = Map.of("Referer", URL_REFERER);
		String content = Web.request(new GetRequest(Utils.url(frameURL), Shared.USER_AGENT, headers)).content;
		
		SSDCollection playerVideos = null;
		if((index = content.indexOf("var playerVideos")) >= 0) {
			String data = Utils.bracketSubstring(content, '[', ']', false, index, content.length());
			playerVideos = JSON.read(data);
		}
		
		// Unable to obtain the player settings
		if(playerVideos == null) {
			return null;
		}
		
		String title = mediaTitle(document);
		MediaSource source = MediaSource.of(this);
		URI sourceURI = Utils.uri(url);
		MediaLanguage language = MediaLanguage.UNKNOWN;
		MediaMetadata metadata = MediaMetadata.builder().title(title).build();
		
		// Add all available media that are found in the player settings
		SSDCollection emptyArray = SSDCollection.emptyArray();
		for(SSDCollection video : playerVideos.collectionsIterable()) {
			SSDCollection additionalURLs = video.getDirectCollection("urls", emptyArray);
			String videoURL = video.getDirectString("url");
			
			List<Media> media = new ArrayList<>();
			media.addAll(MediaUtils.createMedia(source, Utils.uri(videoURL), sourceURI, title, language, metadata));
			
			for(SSDCollection additionalVideo : additionalURLs.collectionsIterable()) {
				String additionalVideoURL = additionalVideo.getDirectString("url");
				media.add(VideoMedia.simple().source(source)
				          	.uri(Utils.uri(additionalVideoURL))
				          	.quality(MediaQuality.fromString(additionalVideo.getDirectString("name"), MediaType.VIDEO))
				          	.format(MediaFormat.fromPath(additionalVideoURL))
				          	.metadata(metadata)
				          	.build());
			}
			
			for(Media m : media) {
				sources.add(m);
				
				if(!function.apply(proxy, m)) {
					return null; // Do not continue
				}
			}
		}
		
		return sources;
	}
	
	private final List<Media> internal_getMedia(Episode episode) throws Exception {
		return internal_getMedia(episode, _dwp, (p, a) -> true);
	}
	
	private final List<Media> internal_getMedia(Episode episode, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
		return internal_getMedia(episode.uri().toString(), proxy, function);
	}
	
	// -----
	
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
		URL urlObj = Utils.url(url);
		// Check the protocol
		String protocol = urlObj.getProtocol();
		if(!protocol.equals("http") &&
		   !protocol.equals("https"))
			return false;
		// Check the host
		String host = urlObj.getHost();
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