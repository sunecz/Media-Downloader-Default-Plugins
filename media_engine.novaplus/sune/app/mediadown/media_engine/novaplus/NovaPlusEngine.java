package sune.app.mediadown.media_engine.novaplus;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.WorkerProxy;
import sune.app.mediadown.util.WorkerUpdatableTask;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDF;

public final class NovaPlusEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// General
	private static final String URL_BASE = URL;
	
	// Programs
	private static final String URL_PROGRAMS      = URL_BASE + "/porady/";
	private static final String SEL_PROGRAMS_LIST = ".e-az-list + .b-tiles-wrapper";
	private static final String SEL_PROGRAMS_ITEM = ".b-tile";
	private static final String SEL_PROGRAMS_LINK = "a";
	private static final String SEL_PROGRAMS_NAME = "h3";
	
	// Episodes
	private static final String SEL_EPISODES_LIST = ".b-articles";
	private static final String SEL_EPISODES_ITEM = ".b-article-news";
	private static final String SEL_EPISODES_LINK = ".b-content a.e-title";
	private static final String SEL_EPISODES_NAME = ".b-content h3.e-title";
	private static final String SEL_EPISODES_LOAD_MORE = ".e-load-more .btn";
	
	// Videos
	private static final String SEL_PLAYER_IFRAME = ".container iframe";
	private static final String TXT_PLAYER_CONFIG_BEGIN = "Player.init(";
	
	// Others
	private static final String SEL_LABEL_VOYO = ".e-label.voyo";
	private static final String URL_PART_WHOLE_EPISODES = "/cele-dily";
	private static final String URL_EPISODE_LIST;
	
	private static final Pattern REGEX_EPISODE = Pattern.compile("^(?:.*?(?: - |: ))?(\\d+)\\. díl(?:(?: - |: )(.*))?$");
	
	static {
		URL_EPISODE_LIST = "https://novaplus.nova.cz/api/v1/mixed/more"
				+ "?page=%{page}d"
				+ "&offset=%{offset}d"
				+ "&content=%{content}s"
				+ "%{excluded}s";
	}
	
	// Allow to create an instance when registering the engine
	NovaPlusEngine() {
	}
	
	// ----- Internal methods
	
	private static final String mediaTitle(SSDCollection streamInfo) {
		// NovaPlus has weird naming, this is actually correct
		String programName = streamInfo.getDirectString("episode", "");
		String episodeText = streamInfo.getDirectString("programName", "");
		int numSeason = streamInfo.getDirectInt("seasonNumber", 0);
		int numEpisode = -1;
		String episodeName = ""; // Use empty string rather than null
		Matcher matcher = REGEX_EPISODE.matcher(episodeText);
		if(matcher.matches()) {
			numEpisode = Integer.parseInt(matcher.group(1));
			episodeName = Optional.ofNullable(matcher.group(2)).orElse("");
		}
		return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
	}
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	private final List<Program> internal_getPrograms() throws Exception {
		return internal_getPrograms(_dwp, (p, a) -> true);
	}
	
	private final List<Program> internal_getPrograms(WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
		List<Program> programs = new ArrayList<>();
		Document document = Utils.document(URL_PROGRAMS);
		Element elList = document.selectFirst(SEL_PROGRAMS_LIST);
		Elements elItems = elList.select(SEL_PROGRAMS_ITEM);
		if((elItems != null)) {
			for(Element elItem : elItems) {
				Element elLink = elItem.selectFirst(SEL_PROGRAMS_LINK);
				if((elLink != null)) {
					String programName = elItem.selectFirst(SEL_PROGRAMS_NAME).text();
					String programURL = elLink.attr("href");
					Program program = new Program(Utils.uri(programURL), programName);
					programs.add(program);
					if(!function.apply(proxy, program))
						return null; // Do not continue
				}
			}
		}
		return programs;
	}
	
	private final int parseEpisodeList(Program program, List<Episode> episodes, Elements elItems,
			WorkerProxy proxy, CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		int counter = 0;
		for(Element elItem : elItems) {
			if((elItem.selectFirst(SEL_LABEL_VOYO)) != null) {
				++counter; continue;
			}
			Elements elLabels = elItem.select(".e-label");
			if((elLabels.size() > 1)) {
				// There is a category label
				Element elCatLabel = elLabels.first();
				// This is not a full episode
				if(elCatLabel == null || (elCatLabel = elCatLabel.selectFirst("a")) == null
						|| !elCatLabel.selectFirst("a").attr("href").endsWith(URL_PART_WHOLE_EPISODES))
					continue;
			}
			Element elLink = elItem.selectFirst(SEL_EPISODES_LINK);
			Element elName = elItem.selectFirst(SEL_EPISODES_NAME);
			if((elLink != null && elName != null)) {
				String episodeURL = elLink.attr("href");
				String episodeName = Utils.validateFileName(elName.text());
				Episode episode = new Episode(program, Utils.uri(episodeURL), Utils.validateFileName(episodeName));
				episodes.add(episode);
				if(!function.apply(proxy, episode))
					return 2; // Do not continue
			}
		}
		// If all episodes are from VOYO, no more free episodes are available
		return counter == elItems.size() ? 1 : 0;
	}
	
	private final int parseEpisodesPage(Program program, List<Episode> episodes, Document document, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		for(Element elList : document.select(SEL_EPISODES_LIST)) {
			Elements elItems = elList.select(SEL_EPISODES_ITEM);
			if((elItems != null)) {
				int result = parseEpisodeList(program, episodes, elItems, proxy, function);
				if((result == 2))
					return 1;
				if((result == 1))
					continue;
			}
		}
		int page = 2, offset = 0;
		String content = null;
		Element btnLoadMore = document.selectFirst(SEL_EPISODES_LOAD_MORE);
		if((btnLoadMore != null)) {
			String href = btnLoadMore.attr("data-href");
			Map<String, String> params = Utils.urlParams(href);
			Map<String, String> excludedMap = params.entrySet().stream()
				.filter((e) -> e.getKey().startsWith("excluded"))
				.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
			// The random variable is here due to caching issue, this should circumvent it
			String random = "&rand=" + String.valueOf(Math.random() * 1000000.0);
			String excluded = random + '&' + Utils.joinURLParams(excludedMap);
			page = Integer.parseInt(params.get("page"));
			offset = Integer.parseInt(params.get("offset"));
			content = params.get("content");
			Map<String, String> headers = new LinkedHashMap<>();
			headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
			headers.put("Pragma", "no-cache");
			headers.put("Expires", "0");
			headers.put("X-Requested-With", "XMLHttpRequest");
			// Load episodes from other pages
			Elements elItems = null;
			do {
				String pageURL = Utils.format(URL_EPISODE_LIST,
					"page", page, "offset", offset, "content", content,
					"excluded", excluded);
				String pageContent = Web.request(new GetRequest(Utils.url(pageURL), Shared.USER_AGENT, headers)).content;
				if((pageContent == null)) continue;
				Document doc = Utils.parseDocument(pageContent, Utils.baseURL(pageURL));
				elItems = doc.select(SEL_EPISODES_ITEM);
				++page;
				offset += 5;
			} while(elItems != null && parseEpisodeList(program, episodes, elItems, proxy, function) == 0);
		}
		return 0;
	}
	
	private final List<Episode> internal_getEpisodes(Program program) throws Exception {
		return internal_getEpisodes(program, _dwp, (p, a) -> true);
	}
	
	private final List<Episode> internal_getEpisodes(Program program, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		List<Episode> episodes = new ArrayList<>();
		Document document = Utils.document(program.uri().toString() + URL_PART_WHOLE_EPISODES);
		// Parse page: All episodes
		if((parseEpisodesPage(program, episodes, document, proxy, function) != 0))
			return null;
		if((episodes.isEmpty()
				// Parse page: All
				&& parseEpisodesPage(program, episodes, Utils.document(program.uri()), proxy, function) != 0))
			return null;
		// Return the list of episodes
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
		if((iframe == null)) return null;
		String iframeURL = iframe.attr("src");
		String content = Web.request(new GetRequest(Utils.url(iframeURL), Shared.USER_AGENT)).content;
		if((content != null && !content.isEmpty())) {
			int begin = content.indexOf(TXT_PLAYER_CONFIG_BEGIN) + TXT_PLAYER_CONFIG_BEGIN.length() - 1;
			String conScript = Utils.bracketSubstring(content, '(', ')', false, begin, content.length());
			conScript = Utils.bracketSubstring(conScript, '{', '}', false, conScript.indexOf('{', 1), conScript.length());
			if(!conScript.isEmpty()) {
				SSDCollection scriptData = SSDF.readJSON(conScript);
				if((scriptData != null)) {
					SSDCollection tracks = scriptData.getCollection("tracks");
					URI sourceURI = Utils.uri(url);
					MediaSource source = MediaSource.of(this);
					for(SSDCollection node : tracks.collectionsIterable()) {
						MediaFormat format = MediaFormat.fromName(node.getName());
						for(SSDCollection coll : ((SSDCollection) node).collectionsIterable()) {
							String videoURL = coll.getDirectString("src");
							if(format == MediaFormat.UNKNOWN)
								format = MediaFormat.fromPath(videoURL);
							MediaLanguage language = MediaLanguage.ofCode(coll.getDirectString("lang"));
							String title = mediaTitle(scriptData.getCollection("plugins.measuring.streamInfo"));
							List<Media> media = MediaUtils.createMedia(source, Utils.uri(videoURL), sourceURI,
								title, language, MediaMetadata.empty());
							for(Media s : media) {
								sources.add(s);
								if(!function.apply(proxy, s))
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
		if(!host.equals("novaplus.nova.cz"))
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