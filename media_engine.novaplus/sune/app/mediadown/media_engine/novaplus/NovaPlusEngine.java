package sune.app.mediadown.media_engine.novaplus;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javafx.scene.image.Image;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.media.Media;
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
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

public final class NovaPlusEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Programs
	private static final String URL_PROGRAMS = "https://tv.nova.cz/porady";
	private static final String SEL_PROGRAMS = ":not(.tab-content) > .c-show-wrapper > .c-show";
	
	// Episodes
	private static final String SEL_EPISODES = ".c-article-wrapper [class^='col-']";
	private static final String SEL_EPISODES_LOAD_MORE = ".js-load-more-trigger .c-button";
	
	// Videos
	private static final String SEL_PLAYER_IFRAME = ".container iframe";
	private static final String TXT_PLAYER_CONFIG_BEGIN = "Player.init(";
	
	// Others
	private static final String SEL_LABEL_VOYO = ".c-badge";
	private static final String URL_EPISODE_LIST;
	
	private static final Regex REGEX_EPISODE = Regex.of("^(?:.*?(?: - |: ))?(\\d+)\\. díl(?:(?: - |: )(.*))?$");
	
	static {
		URL_EPISODE_LIST = "https://tv.nova.cz/api/v1/mixed/more"
				+ "?page=%{page}d"
				+ "&offset=%{offset}d"
				+ "&content=%{content}s"
				+ "%{excluded}s";
	}
	
	// Allow to create an instance when registering the engine
	NovaPlusEngine() {
	}
	
	private static final String mediaTitle(Document document) {
		// Since using the measuring.streamInfo is not reliable, we use Linked Data.
		JSONCollection data = null;
		
		for(Element script : document.select("script[type='application/ld+json']")) {
			JSONCollection content = JSON.read(script.html());
			
			// Skip Linked Data of the website itself
			if(content.getString("@type").equalsIgnoreCase("website")) {
				continue;
			}
			
			data = content;
		}
		
		// The Linked data should always be present
		if(data == null) {
			throw new IllegalStateException("No Linked data");
		}
		
		String programName = data.getString("partOfSeries.name");
		String episodeName = data.getString("name", "");
		int numSeason = data.getInt("partOfSeason.seasonNumber", -1);
		int numEpisode = data.getInt("episodeNumber", -1);
		
		Matcher matcher = REGEX_EPISODE.matcher(episodeName);
		if(matcher.matches()) {
			if(numEpisode < 0) {
				numEpisode = Integer.valueOf(matcher.group(1));
			}
			
			episodeName = Optional.ofNullable(matcher.group(2)).orElse("");
		}
		
		return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
	}
	
	private final int parseEpisodeList(Program program, ListTask<Episode> task, Elements elItems,
			boolean onlyFullEpisodes) throws Exception {
		int counter = 0;
		
		for(Element elItem : elItems) {
			if(elItem.selectFirst(SEL_LABEL_VOYO) != null) {
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
				Episode episode = new Episode(program, Net.uri(episodeURL), Utils.validateFileName(episodeName));
				
				if(!task.add(episode)) {
					return 2; // Do not continue
				}
			}
		}
		
		// If all episodes are from VOYO, no more free episodes are available
		return counter == elItems.size() ? 1 : 0;
	}
	
	private final int parseEpisodesPage(Program program, ListTask<Episode> task, Document document,
			boolean onlyFullEpisodes) throws Exception {
		// Always obtain the first page from the document's content
		Elements elItems = document.select(SEL_EPISODES);
		if(elItems != null
				&& parseEpisodeList(program, task, elItems, onlyFullEpisodes) == 2) {
			return 1; // Do not continue
		}
		
		// Check whether the load more button exists
		Element elLoadMore = document.selectFirst(SEL_EPISODES_LOAD_MORE);
		if(elLoadMore == null) {
			return 0; // No more episodes present, nothing else to do
		}
		
		// If the button exists, load the episodes the dynamic way
		String href = elLoadMore.absUrl("data-href");
		QueryArgument params = Net.queryDestruct(href);
		List<QueryArgument> excludedList = params.arguments().stream()
			.filter((a) -> a.name().startsWith("excluded"))
			.collect(Collectors.toList());
		// The random variable is here due to caching issue, this should circumvent it
		String random = "&rand=" + String.valueOf(Math.random() * 1000000.0);
		String excluded = random + '&' + Net.queryConstruct(excludedList);
		int offset = Integer.valueOf(params.valueOf("offset"));
		String content = params.valueOf("content");
		
		HttpHeaders headers = Web.Headers.ofSingle(
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
			String pageURL = Utils.format(URL_EPISODE_LIST,
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
					pageContent = Web.request(Request.of(Net.uri(pageURL)).headers(headers).GET()).body();
				} catch(SocketTimeoutException ex) {
					timeoutException = ex;
				}
			} while(pageContent == null && ++ctr <= numOfRetries);
			
			// If even retried request timed out, just throw the exception
			if(timeoutException != null) throw timeoutException;
			
			if(pageContent == null) continue;
			Document doc = HTML.parse(pageContent, Net.baseURI(Net.uri(pageURL)));
			elItems = doc.select(SEL_EPISODES);
			
			offset += itemsPerPage;
		} while(elItems != null
					&& parseEpisodeList(program, task, elItems, onlyFullEpisodes) == 0);
		
		return 0;
	}
	
	private final void getPrograms(ListTask<Program> task) throws Exception {
		Document document = HTML.from(Net.uri(URL_PROGRAMS));
		
		for(Element elProgram : document.select(SEL_PROGRAMS)) {
			String programURL = elProgram.absUrl("href");
			String programTitle = elProgram.selectFirst(".title").text();
			Program program = new Program(Net.uri(programURL), programTitle);
			
			if(!task.add(program)) {
				return; // Do not continue
			}
		}
	}
	
	private final void getEpisodes(ListTask<Episode> task, Program program) throws Exception {
		for(String urlPath : List.of("videa/cele-dily", "videa/reprizy")) {
			URI uri = Net.uri(Net.uriConcat(program.uri().toString(), urlPath));
			
			Response.OfString response = Web.request(Request.of(uri).GET());
			if(response.statusCode() != 200) continue; // Probably does not exist, ignore
			
			Document document = HTML.parse(response.body(), uri);
			if(parseEpisodesPage(program, task, document, false) != 0) {
				return; // Do not continue
			}
		}
		
		// If no episodes were found, try to obtain them from the All videos page.
		if(task.isEmpty()) {
			URI uri = Net.uri(Net.uriConcat(program.uri().toString(), "videa"));
			Response.OfString response = Web.request(Request.of(uri).GET());
			
			if(response.statusCode() == 200) {
				Document document = HTML.parse(response.body(), uri);
				
				if(parseEpisodesPage(program, task, document, true) != 0) {
					return; // Do not continue
				}
			}
		}
	}
	
	private final void getMedia(ListTask<Media> task, URI uri, Map<String, Object> data) throws Exception {
		Document document = HTML.from(uri);
		Element iframe = document.selectFirst(SEL_PLAYER_IFRAME);
		
		if(iframe == null) {
			return; // Do not continue
		}
		
		String iframeURL = iframe.absUrl("data-src");
		String content = Web.request(Request.of(Net.uri(iframeURL)).GET()).body();
		
		if(content == null || content.isEmpty()) {
			return; // Do not continue
		}
		
		int begin = content.indexOf(TXT_PLAYER_CONFIG_BEGIN) + TXT_PLAYER_CONFIG_BEGIN.length() - 1;
		String conScript = Utils.bracketSubstring(content, '(', ')', false, begin, content.length());
		conScript = Utils.bracketSubstring(conScript, '{', '}', false, conScript.indexOf('{', 1), conScript.length());
		
		if(!conScript.isEmpty()) {
			JSONCollection scriptData = JavaScript.readObject(conScript);
			
			if(scriptData != null) {
				JSONCollection tracks = scriptData.getCollection("tracks");
				URI sourceURI = uri;
				MediaSource source = MediaSource.of(this);
				String title = mediaTitle(document);
				
				for(JSONCollection node : tracks.collectionsIterable()) {
					for(JSONCollection coll : ((JSONCollection) node).collectionsIterable()) {
						String videoURL = coll.getString("src");
						MediaLanguage language = MediaLanguage.ofCode(coll.getString("lang"));
						List<Media> media = MediaUtils.createMedia(source, Net.uri(videoURL), sourceURI,
							title, language, MediaMetadata.empty());
						
						for(Media s : media) {
							if(!task.add(s)) {
								return; // Do not continue
							}
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