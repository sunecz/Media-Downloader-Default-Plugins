package sune.app.mediadown.media_engine.markizaplus;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.Episode;
import sune.app.mediadown.Program;
import sune.app.mediadown.Shared;
import sune.app.mediadown.engine.MediaEngine;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.util.CheckedBiFunction;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.WorkerProxy;
import sune.app.mediadown.util.WorkerUpdatableTask;
import sune.util.ssdf2.SSDCollection;

public final class MarkizaPlusEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Programs
	private static final String URL_PROGRAMS = "https://videoarchiv.markiza.sk/relacie-a-serialy";
	private static final String SEL_PROGRAMS = ".b-show-listing .b-tiles-wrapper > .b-tile > a";
	
	// Episodes
	private static final String URL_EPISODES;
	private static final String SEL_EPISODES = ".b-episodes .b-article > a";
	private static final String SEL_EPISODES_HEADER = ".b-episodes .b-episodes-header + * .b-article > a";
	private static final String SEL_PLAYLIST = ".b-content-wrapper .video-holder ~ .row aside .b-article > a";
	
	// Media
	private static final String SEL_PLAYER_IFRAME = ".video-holder iframe";
	private static final String TXT_PLAYER_CONFIG_BEGIN = "Player.init(";
	
	// Other
	private static final Regex REGEX_CONTENT_TITLE = Regex.of("(?i)Séria (\\d+), epizóda (\\d+)");
	private static final Regex REGEX_EPISODE_TITLE = Regex.of("^(?:.*?(?: - |: ))?(\\d+)\\. díl(?:(?: - |: )(.*))?$");
	
	static {
		URL_EPISODES = "https://videoarchiv.markiza.sk/api/v1/plugin/broadcasted-episodes"
			+ "?show=%{showId}d"
			+ "&season=%{seasonId}d"
			+ "&count=%{count}d"
			+ "&dir=%{order}s"
			+ "&page=%{page}d";
	}
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	// Allow to create an instance when registering the engine
	MarkizaPlusEngine() {
	}
	
	private static final String apiUrlEpisodes(int showId, int seasonId, int count, int page) {
		return Utils.format(URL_EPISODES, "showId", showId, "seasonId", seasonId, "count", count, "order", "DESC",
		                    "page", page);
	}
	
	private static final boolean parseEpisodesList(Program program, List<Episode> episodes, Document document,
			String selector, WorkerProxy proxy, CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		for(Element elEpisode : document.select(selector)) {
			// Skip episodes that are available only on Voyo
			if(elEpisode.selectFirst(".e-voyo") != null) {
				continue;
			}
			
			URI episodeUri = Utils.uri(elEpisode.absUrl("href"));
			String episodeTitle = elEpisode.selectFirst(".e-title").text();
			Episode episode = new Episode(program, episodeUri, episodeTitle);
			
			episodes.add(episode);
			if(!function.apply(proxy, episode))
				return false; // Do not continue
		}
		
		return true;
	}
	
	private static final boolean loopSeasonEpisodes(Program program, List<Episode> episodes, int showId, int seasonId,
			WorkerProxy proxy, CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		final int itemsPerPage = 32;
		
		for(int page = 1;; ++page) {
			URI uri = Utils.uri(apiUrlEpisodes(showId, seasonId, itemsPerPage, page));
			Document document = Utils.document(uri);
			
			if(!parseEpisodesList(program, episodes, document, SEL_EPISODES, proxy, function)) {
				return false; // Cancelled, do not continue
			}
			
			// If there is no load more button then it is the last page
			if(document.selectFirst(".b-episodes .js-load-next") == null) {
				break;
			}
		}
		
		return true;
	}
	
	private static final String mediaTitle(SSDCollection streamInfo, Document document) {
		// Markiza Plus has weird naming, so this is actually correct
		String programName = streamInfo.getDirectString("episode", "");
		String episodeText = streamInfo.getDirectString("programName", "");
		int numSeason = streamInfo.getDirectInt("seasonNumber", 0);
		int numEpisode = -1;
		String episodeName = ""; // Use empty string rather than null
		
		Matcher matcher = REGEX_EPISODE_TITLE.matcher(episodeText);
		if(matcher.matches()) {
			numEpisode = Integer.valueOf(matcher.group(1));
			episodeName = Optional.ofNullable(matcher.group(2)).orElse("");
		}
		
		Element elContentTitle = null;
		if(numSeason <= 0 || numEpisode <= 0) {
			// Usually the season and episode number is in the title of the video
			elContentTitle = document.selectFirst(".b-content-wrapper > .video-holder ~ .row .e-title");
			
			if(elContentTitle != null
					&& (matcher = REGEX_CONTENT_TITLE.matcher(elContentTitle.text().strip())).find()) {
				numSeason = Integer.valueOf(matcher.group(1));
				numEpisode = Integer.valueOf(matcher.group(2));
			} else {
				elContentTitle = null; // Reset for further checks
			}
		}
		
		if(programName.isEmpty()) {
			// Obtain the program name from the page title
			Element elPageTitle = document.selectFirst(".b-content-wrapper h2");
			
			if(elPageTitle != null) {
				programName = elPageTitle.text().strip();
			}
		}
		
		if(episodeName.isEmpty()) {
			// Obtain the episode name from the content title
			if(elContentTitle != null) {
				// If the content title contains the season and episode number, remove it first
				int start = matcher.start(0);
				int end = matcher.end(0);
				String text = elContentTitle.text().strip();
				
				if(start > 0) {
					episodeName = text.substring(0, start);
				}
				
				if(end < text.length()) {
					episodeName += text.substring(end);
				}
			} else {
				elContentTitle = document.selectFirst(".b-content-wrapper > .video-holder ~ .row .e-title");
				episodeName = elContentTitle.text().strip();
			}
		}
		
		return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
	}
	
	// ----- Internal methods
	
	private final List<Program> internal_getPrograms() throws Exception {
		return internal_getPrograms(_dwp, (p, a) -> true);
	}
	
	private final List<Program> internal_getPrograms(WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
		List<Program> programs = new ArrayList<>();
		
		Document document = Utils.document(URL_PROGRAMS);
		for(Element elProgram : document.select(SEL_PROGRAMS)) {
			String programURL = elProgram.absUrl("href");
			String programTitle = elProgram.selectFirst("h3").text();
			Program program = new Program(Utils.uri(programURL), programTitle);
			
			programs.add(program);
			if(!function.apply(proxy, program)) {
				return null; // Do not continue
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
		
		final Regex regexShowId = Regex.of("show-(\\d+)");
		final Regex regexSeasonId = Regex.of("seasonId=(\\d+)");
		
		// Obtain the show ID so that we can use it later in the API calls.
		// There should exist a script with a classes list that contains a special class
		// with the show ID in it.
		int showId = -1;
		for(Element elScript : document.select("head > script:not([src])")) {
			String content = elScript.html();
			Matcher matcher;
			
			if((matcher = regexShowId.matcher(content)).find()) {
				showId = Integer.valueOf(matcher.group(1));
				break; // Show ID found, no need to continue
			}
		}
		
		// Obtain all available seasons from the dropdown menu, if it exists
		List<Integer> seasons = new ArrayList<>();
		for(Element elSeasonItem : document.select(".b-episodes-dropdown > .dropdown-menu > .js-episodes-season")) {
			String seasonDataUrl = elSeasonItem.attr("data-url");
			Matcher matcher;
			
			if((matcher = regexSeasonId.matcher(seasonDataUrl)).find()) {
				int seasonId = Integer.valueOf(matcher.group(1));
				seasons.add(seasonId);
			}
		}
		
		// If the list does not exist, obtain the only season ID from the load more button
		if(seasons.isEmpty()) {
			Element elButtonLoadMore = document.selectFirst(".b-episodes .js-load-next");
			
			if(elButtonLoadMore != null) {
				final Regex regexSeasonIdButton = Regex.of("season=(\\d+)");
				String loadMoreUrl = elButtonLoadMore.attr("href");
				Matcher matcher;
				
				if((matcher = regexSeasonIdButton.matcher(loadMoreUrl)).find()) {
					int seasonId = Integer.valueOf(matcher.group(1));
					seasons.add(seasonId);
				}
			}
		}
		
		if(!seasons.isEmpty()) {
			// Load all episodes from all found seasons
			for(int seasonId : seasons) {
				if(!loopSeasonEpisodes(program, episodes, showId, seasonId, proxy, function)) {
					break; // Cancelled, do not continue
				}
			}
		} else {
			// If there are no seasons, just load the episodes from the document
			parseEpisodesList(program, episodes, document, SEL_EPISODES_HEADER, proxy, function);
		}
		
		// If there are still no episodes, try to obtain them from the playlist
		if(episodes.isEmpty()) {
			Element elFirstEpisode = document.selectFirst(SEL_EPISODES_HEADER);
			
			// Must check whether the playlist is even valid (i.e. there is no free episodes list)
			if(elFirstEpisode == null || elFirstEpisode.selectFirst(".e-voyo") != null) {
				parseEpisodesList(program, episodes, document, SEL_PLAYLIST, proxy, function);
			}
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
		Element iframe = document.selectFirst(SEL_PLAYER_IFRAME);
		
		if(iframe == null) {
			return null;
		}
		
		String iframeUrl = iframe.absUrl("src");
		String content = Web.request(new GetRequest(Utils.url(iframeUrl), Shared.USER_AGENT)).content;
		
		if(content != null && !content.isEmpty()) {
			int begin = content.indexOf(TXT_PLAYER_CONFIG_BEGIN) + TXT_PLAYER_CONFIG_BEGIN.length() - 1;
			String conScript = Utils.bracketSubstring(content, '(', ')', false, begin, content.length());
			conScript = Utils.bracketSubstring(conScript, '{', '}', false, conScript.indexOf('{', 1), conScript.length());
			
			if(!conScript.isEmpty()) {
				SSDCollection scriptData = JSON.read(conScript);
				
				if(scriptData != null) {
					SSDCollection tracks = scriptData.getCollection("tracks");
					URI sourceUri = Utils.uri(url);
					MediaSource source = MediaSource.of(this);
					
					for(SSDCollection node : tracks.collectionsIterable()) {
						MediaFormat format = MediaFormat.fromName(node.getName());
						
						for(SSDCollection coll : ((SSDCollection) node).collectionsIterable()) {
							String videoUrl = coll.getDirectString("src");
							
							if(format == MediaFormat.UNKNOWN) {
								format = MediaFormat.fromPath(videoUrl);
							}
							
							MediaLanguage language = MediaLanguage.ofCode(coll.getDirectString("lang"));
							String title = mediaTitle(scriptData.getCollection("plugins.measuring.streamInfo"), document);
							List<Media> media = MediaUtils.createMedia(source, Utils.uri(videoUrl), sourceUri,
								title, language, MediaMetadata.empty());
							
							for(Media m : media) {
								sources.add(m);
								if(!function.apply(proxy, m))
									return null; // Do not continue
							}
						}
					}
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
		if(!host.equals("videoarchiv.markiza.sk"))
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