package sune.app.mediadown.media_engine.novaplus;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javafx.scene.image.Image;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.gui.Dialog;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Net.QueryArgument;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Ref;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

public final class NovaPlusEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// URLs
	private static final String URL_PROGRAMS = "https://tv.nova.cz/porady";
	private static final String URL_EPISODE_LIST = "https://tv.nova.cz/api/v1/mixed/more"
		+ "?page=0"
		+ "&offset=%{offset}d"
		+ "&content=%{content}s";
	
	// Selectors
	private static final String SEL_PROGRAMS = ":not(.tab-content) > .c-show-wrapper > .c-show";
	private static final String SEL_EPISODES = ".c-article-wrapper [class^='col-'] .c-article";
	private static final String SEL_EPISODES_LOAD_MORE = ".js-article-load-more .c-button";
	private static final String SEL_PLAYER_IFRAME = "iframe[data-video-id]";
	private static final String SEL_LABEL_VOYO = ".c-badge";
	
	// Others
	private static final String TXT_PLAYER_CONFIG_BEGIN = "player:";
	private static final int RESULT_EXIT = -1;
	
	// Regex
	private static final Regex REGEX_EPISODE = Regex.of("(?iu)^(?:.*?(?: - |: ))?(\\d+)\\. díl(?:(?: - |: )(.*))?$");
	private static final Regex REGEX_EPISODE_TITLE = Regex.of(
		"(?iu)(?:(?:\\s+[\\-\\u2013\\u2014]|\\s*:)\\s+)?(\\d+)\\.\\s+díl(?:\\s+[\\-\\u2013\\u2014]\\s+)?"
	);
	
	// Allow to create an instance when registering the engine
	NovaPlusEngine() {
	}
	
	private static final Translation translation() {
		String path = "plugin." + PLUGIN.getContext().getPlugin().instance().name();
		return MediaDownloader.translation().getTranslation(path);
	}
	
	private static final JSONCollection linkedData(Document document, Set<String> types) {
		for(Element script : document.select("script[type='application/ld+json']")) {
			JSONCollection content = JSON.read(script.html());
			
			// Skip Linked Data of the website itself
			if(types.contains(content.getString("@type"))) {
				return content;
			}
		}
		
		return null;
	}
	
	private static final String mediaTitle(Document document) {
		// Since using the measuring.streamInfo is not reliable, we use Linked Data.
		JSONCollection data = linkedData(document, Set.of("TVEpisode", "Article", "VideoObject"));
		
		// The Linked data should always be present
		if(data == null) {
			throw new IllegalStateException("No Linked data");
		}
		
		String programName = data.getString("partOfSeries.name");
		String episodeName = data.getString("name", "");
		int numSeason = data.getInt("partOfSeason.seasonNumber", -1);
		int numEpisode = data.getInt("episodeNumber", -1);
		Matcher matcher;
		
		if(programName == null) {
			// Alternative "program name" for a VideoObject
			if(!episodeName.isEmpty()) {
				programName = episodeName;
				episodeName = "";
			} else {
				// Alternative "program name" for an Article
				programName = data.getString("headline");
			}
		}
		
		if((matcher = REGEX_EPISODE.matcher(episodeName)).matches()) {
			if(numEpisode < 0) {
				numEpisode = Integer.valueOf(matcher.group(1));
			}
			
			episodeName = Optional.ofNullable(matcher.group(2)).orElse("");
		}
		
		return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
	}
	
	private final int parseEpisodeList(Program program, ListTask<Episode> task, Elements elItems,
			boolean onlyFullEpisodes, int index) throws Exception {
		int counter = elItems.size();
		Regex regexProgramTitle = Regex.of(
			"(?iu)^" + Regex.quote(program.title()) + "(?:\\s+[\\-\\u2013\\u2014]|\\s*:)?\\s*"
		);
		
		// Loop through the episodes in reverse since this method is also used when no
		// episode number is present in an episode title, so the index is actually used.
		for(Element elItem : Utils.asReversed(elItems)) {
			// Check whether it is a VOYO-only episode
			if(elItem.selectFirst(SEL_LABEL_VOYO) != null) {
				--counter;
				continue; // Skip the episode
			}
			
			// When searching videos, episodes are specially marked, if required,
			// select only those.
			if(onlyFullEpisodes
					&& elItem.selectFirst(".c-article[data-tracking-tile-asset='episode']") == null) {
				continue; // Skip the episode
			}
			
			Element elLink = elItem.selectFirst(".title > a");
			Matcher matcher;
			
			URI uri = Net.uri(elLink.absUrl("href"));
			String title = elLink.text();
			int numSeason = 0; // There is no season number available
			int numEpisode = 0;
			
			// Try to obtain the episode number from the episode title
			if((matcher = REGEX_EPISODE_TITLE.matcher(title)).find()) {
				numEpisode = Utils.OfString.asInt(matcher.group(1));
				title = Utils.OfString.delete(title, matcher.start(), matcher.end());
			} else {
				numEpisode = index++;
			}
			
			// Remove the program title from the title when it is present as a prefix
			if((matcher = regexProgramTitle.matcher(title)).find()) {
				title = title.substring(matcher.end());
			}
			
			Element elSubheading = elItem.selectFirst(".content > .category");
			
			if(elSubheading != null) {
				String prefix = elSubheading.text();
				
				if(!prefix.equalsIgnoreCase(program.title())) {
					title = prefix + (title.isEmpty() ? "" : " - " + title);
				}
			}
			
			Episode episode = new Episode(program, uri, title, numEpisode, numSeason);
			
			if(!task.add(episode)) {
				return RESULT_EXIT; // Do not continue
			}
		}
		
		return counter;
	}
	
	private final boolean hasEpisodeNumberInTitle(Element elItem) {
		Element elLink = elItem.selectFirst(".title > a");
		String title = elLink.text();
		return REGEX_EPISODE_TITLE.matcher(title).find();
	}
	
	private final String callUriTemplate(Element btnLoadMore) {
		QueryArgument urlArgs = Net.queryDestruct(btnLoadMore.absUrl("data-href"));
		String contentId = urlArgs.valueOf("content", null);
		
		// In the latest version of the website (as of date 2024-05-07) the 'content'
		// argument's name in the query arguments list may be empty, '0' when parsed.
		// Obtain it using that name instead.
		if(contentId == null) {
			contentId = urlArgs.valueOf("0");
		}
		
		return Utils.format(URL_EPISODE_LIST, "content", contentId);
	}
	
	private final URI callUri(String template, int offset) {
		return Net.uri(Utils.format(template, "offset",  offset));
	}
	
	private final boolean parseEpisodesPage(Program program, ListTask<Episode> task, Document document,
			boolean onlyFullEpisodes, Ref.Mutable<Integer> refIndex) throws Exception {
		Elements elItems = document.select(SEL_EPISODES);
		Element btnLoadMore = document.selectFirst(SEL_EPISODES_LOAD_MORE);
		
		// First, check whether the episodes have the episode number in their title
		if(!elItems.isEmpty()
				&& btnLoadMore != null // There have to be more episodes to search for
				&& !hasEpisodeNumberInTitle(elItems.get(0))) {
			// Since there is no episode number in the title, we must set the episode number
			// as the position in the list. The list is sorted in the descending order, so we
			// must first find the end of the list.
			// For this we can use doubling followed by binary search, so that it scales
			// logarithmically rather than linearly. 
			int count = 6, lo = 0, hi = count;
			
			String callUriTemplate = callUriTemplate(btnLoadMore);
			int index = refIndex.get();
			
			// First, find the actual end offset.
			for(; !Web.request(Request.of(callUri(callUriTemplate, hi)).GET()).body().trim().isEmpty(); hi *= 2);
			
			// Then, find the last offset that have any non-Voyo episodes
			while(hi - lo > count) {
				int mid = lo + (hi - lo) / 2;
				document = HTML.parse(Web.request(Request.of(callUri(callUriTemplate, mid)).GET()).body());
				boolean allVoyo = document.select(SEL_EPISODES).size() == document.select(SEL_LABEL_VOYO).size();
				if(allVoyo) hi = mid; else lo = mid;
			}
			
			// Loop through the episodes in the reverse order
			for(int offset = lo, min = -count, result; offset >= min; offset -= count, index += result) {
				URI callUri = callUri(callUriTemplate, offset);
				document = HTML.parse(Web.request(Request.of(callUri).retry(5).GET()).body());
				result = parseEpisodeList(program, task, document.select(SEL_EPISODES), onlyFullEpisodes, index);
				if(result == RESULT_EXIT) return false;
			}
			
			refIndex.set(index);
			return true;
		}
		
		// Always obtain the first episodes from the static content
		if(parseEpisodeList(program, task, elItems, onlyFullEpisodes, 0) == RESULT_EXIT) {
			return false; // Do not continue
		}
		
		if(btnLoadMore == null) {
			return true; // No more episodes present
		}
		
		String callUriTemplate = callUriTemplate(btnLoadMore);
		
		for(int offset = 0, result;; offset += elItems.size()) {
			URI callUri = callUri(callUriTemplate, offset);
			document = HTML.parse(Web.request(Request.of(callUri).retry(5).GET()).body());
			result = parseEpisodeList(program, task, document.select(SEL_EPISODES), onlyFullEpisodes, 0);
			if(result == 0) break; // No more non-Voyo episodes
			if(result == RESULT_EXIT) return false;
		}
		
		return true;
	}
	
	private final boolean extractEpisodes(ListTask<Episode> task, Program program, String uriPath,
			boolean onlyFullEpisodes, Ref.Mutable<Integer> index) throws Exception {
		URI uri = Net.uri(Net.uriConcat(program.uri().toString(), uriPath));
		Response.OfString response = Web.request(Request.of(uri).GET());
		
		if(response.statusCode() != 200) {
			return true; // Probably does not exist, ignore
		}
		
		Document document = HTML.parse(response.body(), uri);
		return parseEpisodesPage(program, task, document, onlyFullEpisodes, index);
	}
	
	private final void getPrograms(ListTask<Program> task) throws Exception {
		Document document = HTML.from(Net.uri(URL_PROGRAMS));
		
		for(Element elProgram : document.select(SEL_PROGRAMS)) {
			URI uri = Net.uri(elProgram.absUrl("href"));
			String title = elProgram.selectFirst(".title").text();
			Program program = new Program(uri, title);
			
			if(!task.add(program)) {
				return; // Do not continue
			}
		}
	}
	
	private final void getEpisodes(ListTask<Episode> task, Program program) throws Exception {
		Ref.Mutable<Integer> index = new Ref.Mutable<>(1);
		
		for(String uriPath : List.of("videa/reprizy", "videa/cele-dily")) {
			if(!extractEpisodes(task, program, uriPath, false, index)) {
				return; // Do not continue
			}
		}
	}
	
	private final void getMedia(ListTask<Media> task, URI uri, Map<String, Object> data) throws Exception {
		Document document = HTML.from(uri);
		Element iframe = document.selectFirst(SEL_PLAYER_IFRAME);
		
		if(iframe == null) {
			return; // Do not continue
		}
		
		String iframeURL = iframe.absUrl("src");
		String content = Web.request(Request.of(Net.uri(iframeURL)).GET()).body();
		
		if(content == null || content.isEmpty()) {
			return; // Do not continue
		}
		
		int begin = content.indexOf(TXT_PLAYER_CONFIG_BEGIN);
		
		// Video is not available, probably due to licensing issues
		if(begin < 0) {
			Element elTitle = HTML.parse(content).selectFirst(".b-player .e-title");
			String message = Optional.ofNullable(elTitle).map(Element::text).orElse(null);
			String trPath = "error.media_unavailable" + (message == null ? "_no_content" : "");
			Translation tr = translation().getTranslation(trPath);
			String title = tr.getSingle("title");
			String text = tr.getSingle("text");
			if(message == null) Dialog.showInfo(title, text);
			else Dialog.showContentInfo(title, text, message);
			return; // Do not continue
		}
		
		begin += TXT_PLAYER_CONFIG_BEGIN.length();
		String conScript = Utils.bracketSubstring(content, '{', '}', false, begin, content.length());
		
		if(!conScript.isEmpty()) {
			JSONCollection scriptData = JavaScript.readObject(conScript);
			
			if(scriptData != null) {
				JSONCollection tracks = scriptData.getCollection("lib.source.sources");
				URI sourceUri = uri;
				MediaSource source = MediaSource.of(this);
				String title = mediaTitle(document);
				
				for(JSONCollection node : tracks.collectionsIterable()) {
					String type = node.getString("type");
					MediaFormat format = MediaFormat.fromMimeType(type);
					URI videoUri = Net.uri(node.getString("src"));
					MediaLanguage language = MediaLanguage.UNKNOWN;
					MediaMetadata metadata = MediaMetadata.empty();
					
					if(node.hasCollection("contentProtection")) {
						JSONCollection drmInfo = node.getCollection("contentProtection");
						String drmToken = null;
						
						switch(format.name().toLowerCase()) {
							case "dash":
								drmToken = drmInfo.getString("token");
								break;
							default:
								// Widevine not supported, do not add media sources
								continue;
						}
						
						if(drmToken != null) {
							metadata = MediaMetadata.of("drmToken", drmToken);
						}
					}
					
					List<Media> media = MediaUtils.createMedia(
						source, videoUri, sourceUri, title, language, metadata
					);
					
					for(Media s : media) {
						if(!task.add(s)) {
							return; // Do not continue
						}
					}
				}
			}
		}
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> getPrograms(task));
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return ListTask.of((task) -> getEpisodes(task, program));
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> getMedia(task, uri, data));
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
		if(!host.equals("tv.nova.cz"))
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