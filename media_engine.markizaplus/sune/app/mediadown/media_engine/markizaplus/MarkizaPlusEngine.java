package sune.app.mediadown.media_engine.markizaplus;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

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
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.util.CheckedBiFunction;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Web.StringResponse;
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
	private static final String URL_PROGRAMS = "https://www.markiza.sk/relacie";
	private static final String SEL_PROGRAMS = ":not(.tab-content) > .c-show-wrapper > .c-show";
	
	// Episodes
	private static final String URL_EPISODES;
	private static final String SEL_EPISODES = ".c-article-wrapper [class^='col-']";
	private static final String SEL_EPISODES_LOAD_MORE = ".js-load-more-trigger .c-button";
	
	// Media
	private static final String SEL_PLAYER_IFRAME = ".iframe-wrap iframe";
	private static final String TXT_PLAYER_CONFIG_BEGIN = "Player.init(";
	
	// Other
	private static final Regex REGEX_CONTENT_TITLE = Regex.of("(?i)Séria (\\d+), epizóda (\\d+)");
	private static final Regex REGEX_EPISODE_TITLE = Regex.of("^(?:.*?(?: - |: ))?(\\d+)\\. díl(?:(?: - |: )(.*))?$");
	
	static {
		URL_EPISODES = "https://www.markiza.sk/api/v1/mixed/more"
			+ "?page=%{page}d"
			+ "&offset=%{offset}d"
			+ "&content=%{content}s"
			+ "%{excluded}s";
	}
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	// Allow to create an instance when registering the engine
	MarkizaPlusEngine() {
	}
	
	private final int parseEpisodeList(Program program, List<Episode> episodes, Elements elItems,
			boolean onlyFullEpisodes, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		int counter = 0;
		
		for(Element elItem : elItems) {
			if(elItem.selectFirst(".-voyo") != null) {
				++counter; continue;
			}
			
			if(onlyFullEpisodes
					&& elItem.selectFirst(".c-article[data-tracking-tile-asset=\"episode\"]") == null) {
				continue;
			}
			
			Element elLink = elItem.selectFirst(".title > a");
			if(elLink != null) {
				String episodeURL = elLink.absUrl("href");
				String episodeName = elLink.text();
				Episode episode = new Episode(program, Utils.uri(episodeURL), Utils.validateFileName(episodeName));
				episodes.add(episode);
				if(!function.apply(proxy, episode))
					return 2; // Do not continue
			}
		}
		
		// If all episodes are from VOYO, no more free episodes are available
		return counter == elItems.size() ? 1 : 0;
	}
	
	private final int parseEpisodesPage(Program program, List<Episode> episodes, Document document,
			boolean onlyFullEpisodes, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		// Always obtain the first page from the document's content
		Elements elItems = document.select(SEL_EPISODES);
		if(elItems != null
				&& parseEpisodeList(program, episodes, elItems, onlyFullEpisodes, proxy, function) == 2) {
			return 1; // Do not continue
		}
		
		// Check whether the load more button exists
		Element elLoadMore = document.selectFirst(SEL_EPISODES_LOAD_MORE);
		if(elLoadMore == null) {
			return 0; // No more episodes present, nothing else to do
		}
		
		// If the button exists, load the episodes the dynamic way
		String href = elLoadMore.absUrl("data-href");
		Map<String, String> params = Utils.urlParams(href);
		Map<String, String> excludedMap = params.entrySet().stream()
			.filter((e) -> e.getKey().startsWith("excluded"))
			.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		// The random variable is here due to caching issue, this should circumvent it
		String random = "&rand=" + String.valueOf(Math.random() * 1000000.0);
		String excluded = random + '&' + Utils.joinURLParams(excludedMap);
		int offset = Integer.valueOf(params.get("offset"));
		String content = params.get("content");
		
		Map<String, String> headers = Map.of(
			"Cache-Control", "no-cache, no-store, must-revalidate",
			"Pragma", "no-cache",
			"Expires", "0",
			"X-Requested-With", "XMLHttpRequest"
		);
		
		// The offset can be negative, therefore we can use it to obtain the first page
		// with non-filtered/altered content.
		final int itemsPerPage = elItems != null ? elItems.size() : 0;
		final int constPage = 2; // Must be > 1
		
		// Load episodes from other pages
		elItems = null;
		do {
			String pageURL = Utils.format(URL_EPISODES,
				"page",     constPage,
				"offset",   offset,
				"content",  content,
				"excluded", excluded
			);
			String pageContent = null;
			Exception timeoutException = null;
			int ctr = 0;
			int numOfRetries = 5;
			
			// Sometimes a timeout can occur, retry to obtain the content again if it is null
			do {
				timeoutException = null;
				
				try {
					pageContent = Web.request(new GetRequest(Utils.url(pageURL), Shared.USER_AGENT, headers)).content;
				} catch(SocketTimeoutException ex) {
					timeoutException = ex;
				}
			} while(pageContent == null && ++ctr <= numOfRetries);
			
			// If even retried request timed out, just throw the exception
			if(timeoutException != null) throw timeoutException;
			
			if(pageContent == null) continue;
			Document doc = Utils.parseDocument(pageContent, Utils.baseURL(pageURL));
			elItems = doc.select(SEL_EPISODES);
			
			offset += itemsPerPage;
		} while(elItems != null
					&& parseEpisodeList(program, episodes, elItems, onlyFullEpisodes, proxy, function) == 0);
		
		return 0;
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
			elContentTitle = document.selectFirst(".c-hero .c-title");
			
			if(elContentTitle != null
					&& (matcher = REGEX_CONTENT_TITLE.matcher(elContentTitle.text().strip())).find()) {
				numSeason = Integer.valueOf(matcher.group(1));
				numEpisode = Integer.valueOf(matcher.group(2));
			} else {
				elContentTitle = null; // Reset for further checks
			}
		}
		
		if(programName.isEmpty()) {
			// Obtain the program name from the page navigation
			Element elNavProgramItem = document.selectFirst(".c-breadcrumbs li:nth-child(2) > a");
			
			if(elNavProgramItem != null) {
				programName = elNavProgramItem.text().strip();
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
				elContentTitle = document.selectFirst(".c-hero .c-title");
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
		
		for(String urlPath : List.of("videa/cele-epizody")) {
			URI uri = Utils.uri(Utils.urlConcat(program.uri().toString(), urlPath));
			
			StringResponse response = Web.request(new GetRequest(Utils.url(uri), Shared.USER_AGENT));
			if(response.code != 200) continue; // Probably does not exist, ignore
			
			Document document = Utils.parseDocument(response.content, uri);
			if(parseEpisodesPage(program, episodes, document, false, proxy, function) != 0)
				return null;
		}
		
		// If no episodes were found, try to obtain them from the All videos page.
		if(episodes.isEmpty()) {
			URI uri = Utils.uri(Utils.urlConcat(program.uri().toString(), "videa"));
			StringResponse response = Web.request(new GetRequest(Utils.url(uri), Shared.USER_AGENT));
			
			if(response.code == 200) {
				Document document = Utils.parseDocument(response.content, uri);
				
				if(parseEpisodesPage(program, episodes, document, true, proxy, function) != 0)
					return null;
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
		
		String iframeUrl = iframe.absUrl("data-src");
		String content = Web.request(new GetRequest(Utils.url(iframeUrl), Shared.USER_AGENT)).content;
		
		if(content != null && !content.isEmpty()) {
			int begin = content.indexOf(TXT_PLAYER_CONFIG_BEGIN) + TXT_PLAYER_CONFIG_BEGIN.length() - 1;
			String conScript = Utils.bracketSubstring(content, '(', ')', false, begin, content.length());
			conScript = Utils.bracketSubstring(conScript, '{', '}', false, conScript.indexOf('{', 1), conScript.length());
			
			if(!conScript.isEmpty()) {
				SSDCollection scriptData = JavaScript.readObject(conScript);
				
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
		if(!host.equals("markiza.sk"))
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