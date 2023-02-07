package sune.app.mediadown.media_engine.tncz;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javafx.scene.image.Image;
import sune.app.mediadown.Episode;
import sune.app.mediadown.Program;
import sune.app.mediadown.Shared;
import sune.app.mediadown.engine.MediaEngine;
import sune.app.mediadown.media.Media;
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

public final class TNCZEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Programs
	private static final String URL_PROGRAMS = "https://tn.nova.cz/videa";
	private static final String SEL_PROGRAMS = ".c-article-carousel .swiper-slide > a";
	
	// Episodes
	private static final String SEL_EPISODES = ".c-article-wrapper .c-article .title > a";
	private static final String SEL_EPISODES_LOAD_MORE = ".load-more > button";
	private static final String URL_EPISODE_LIST;
	
	// Videos
	private static final String SEL_PLAYER_IFRAME = "iframe.player-container";
	private static final String TXT_PLAYER_CONFIG_BEGIN = "Player.init(";
	
	// Others
	private static final Regex REGEX_SHOW_ID = Regex.of("\"show\":\"(\\d+)\"");
	private static final Regex REGEX_EPISODE = Regex.of("^(?:.*?(?: - |: ))?(\\d+)\\. díl(?:(?: - |: )(.*))?$");
	
	static {
		URL_EPISODE_LIST = "https://tn.nova.cz/api/v1/episodes/more"
			+ "?channel=%{channel}d"
			+ "&limit=%{limit}d"
			+ "&page=%{page}d"
			+ "&filter=%%7B%%22show%%22%%3A%%22%{show}d%%22%%7D"
			+ "&content=%{content}d";
	}
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	// Allow to create an instance when registering the engine
	TNCZEngine() {
	}
	
	// ----- Internal methods
	
	private static final String mediaTitle(SSDCollection streamInfo) {
		// TV Nova has weird naming, this is actually correct
		String programName = streamInfo.getDirectString("episode", "");
		String episodeText = streamInfo.getDirectString("programName", "");
		int numSeason = streamInfo.getDirectInt("seasonNumber", 0);
		int numEpisode = -1;
		String episodeName = ""; // Use empty string rather than null
		
		Matcher matcher = REGEX_EPISODE.matcher(episodeText);
		if(matcher.matches()) {
			numEpisode = Integer.valueOf(matcher.group(1));
			episodeName = Optional.ofNullable(matcher.group(2)).orElse("");
		} else {
			// Use just the episode text, since it contains even the program name
			programName = episodeText;
		}
		
		return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
	}
	
	private final List<Program> internal_getPrograms() throws Exception {
		return internal_getPrograms(_dwp, (p, a) -> true);
	}
	
	private final List<Program> internal_getPrograms(WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
		List<Program> programs = new ArrayList<>();
		Document document = Utils.document(URL_PROGRAMS);
		
		for(Element elProgram : document.select(SEL_PROGRAMS)) {
			String programURL = elProgram.absUrl("href");
			String programTitle = elProgram.selectFirst(".title").text();
			Program program = new Program(Utils.uri(programURL), programTitle);
			
			programs.add(program);
			if(!function.apply(proxy, program)) {
				return null; // Do not continue
			}
		}
		
		return programs;
	}
	
	private final boolean parseEpisodeList(Program program, List<Episode> episodes, Document document,
			WorkerProxy proxy, CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		Elements elItems = document.select(SEL_EPISODES);
		
		for(Element elItem : elItems) {
			String episodeURL = elItem.absUrl("href");
			String episodeName = elItem.text();
			
			Element elItemContent = elItem.parent().parent();
			Element elDateTime = elItemContent.selectFirst(".article-info > time");
			
			// Add date and time information to the episode title, if present
			if(elDateTime != null) {
				String textContent = elDateTime.text();
				
				if(!textContent.isEmpty()) {
					// Include only the formatted date without time
					episodeName += " (" + textContent.replaceFirst(",.*$", "") + ")";
				}
			}
			
			Episode episode = new Episode(program, Utils.uri(episodeURL), Utils.validateFileName(episodeName));
			
			episodes.add(episode);
			if(!function.apply(proxy, episode)) {
				return false; // Do not continue
			}
		}
		
		return true;
	}
	
	private final List<Episode> internal_getEpisodes(Program program) throws Exception {
		return internal_getEpisodes(program, _dwp, (p, a) -> true);
	}
	
	private final List<Episode> internal_getEpisodes(Program program, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		List<Episode> episodes = new ArrayList<>();
		Document document = Utils.document(program.uri());
		
		// Always parse the episodes page itself
		if(!parseEpisodeList(program, episodes, document, proxy, function)) {
			return null; // Do not continue
		}
		
		// Check for more episodes
		Element elButtonMore;
		if((elButtonMore = document.selectFirst(SEL_EPISODES_LOAD_MORE)) != null) {
			Map<String, String> urlArgs = Utils.urlParams(elButtonMore.attr("data-href"));
			
			int channel = Integer.valueOf(urlArgs.get("channel"));
			int content = Integer.valueOf(urlArgs.get("content"));
			String strFilter = urlArgs.get("filter");
			
			Matcher matcherFilter;
			if(!(matcherFilter = REGEX_SHOW_ID.matcher(strFilter)).find()) {
				throw new IllegalStateException("Cannot match the filter argument");
			}
			
			int show = Integer.valueOf(matcherFilter.group(1));
			int page = 2, limit = 20;
			
			do {
				String url = Utils.format(
					URL_EPISODE_LIST,
					"channel", channel,
					"limit", limit,
					"page", page,
					"show", show,
					"content", content
				);
				
				StringResponse response = Web.request(new GetRequest(Utils.url(url), Shared.USER_AGENT));
				document = Utils.parseDocument(response.content, url);
				
				if(!parseEpisodeList(program, episodes, document, proxy, function)) {
					return null; // Do not continue
				}
				
				++page;
			} while(document.selectFirst(SEL_EPISODES_LOAD_MORE) != null);
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
		
		String iframeURL = iframe.absUrl("src");
		String content = Web.request(new GetRequest(Utils.url(iframeURL), Shared.USER_AGENT)).content;
		
		if(content != null && !content.isEmpty()) {
			int begin = content.indexOf(TXT_PLAYER_CONFIG_BEGIN) + TXT_PLAYER_CONFIG_BEGIN.length() - 1;
			String conScript = Utils.bracketSubstring(content, '(', ')', false, begin, content.length());
			conScript = Utils.bracketSubstring(conScript, '{', '}', false, conScript.indexOf('{', 1), conScript.length());
			
			if(!conScript.isEmpty()) {
				SSDCollection scriptData = JavaScript.readObject(conScript);
				
				if(scriptData != null) {
					SSDCollection tracks = scriptData.getCollection("tracks");
					String title = mediaTitle(scriptData.getCollection("plugins.measuring.streamInfo"));
					URI sourceURI = Utils.uri(url);
					MediaSource source = MediaSource.of(this);
					
					for(SSDCollection node : tracks.collectionsIterable()) {
						for(SSDCollection coll : ((SSDCollection) node).collectionsIterable()) {
							String videoURL = coll.getDirectString("src");
							MediaLanguage language = MediaLanguage.ofCode(coll.getDirectString("lang"));
							List<Media> media = MediaUtils.createMedia(source, Utils.uri(videoURL), sourceURI,
								title, language, MediaMetadata.empty());
							
							for(Media s : media) {
								sources.add(s);
								if(!function.apply(proxy, s)) {
									return null; // Do not continue
								}
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
		if(!host.equals("tn.nova.cz"))
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