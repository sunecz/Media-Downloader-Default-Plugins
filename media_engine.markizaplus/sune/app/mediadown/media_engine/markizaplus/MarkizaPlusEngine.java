package sune.app.mediadown.media_engine.markizaplus;

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
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.gui.Dialog;
import sune.app.mediadown.language.Translation;
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
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

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
	
	// Allow to create an instance when registering the engine
	MarkizaPlusEngine() {
	}
	
	private static final Translation translation() {
		String path = "plugin." + PLUGIN.getContext().getPlugin().instance().name();
		return MediaDownloader.translation().getTranslation(path);
	}
	
	private final int parseEpisodeList(ListTask<Episode> task, Program program, Elements elItems,
			boolean onlyFullEpisodes) throws Exception {
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
				Episode episode = new Episode(program, Net.uri(episodeURL), Utils.validateFileName(episodeName));
				
				if(!task.add(episode)) {
					return 2; // Do not continue
				}
			}
		}
		
		// If all episodes are from VOYO, no more free episodes are available
		return counter == elItems.size() ? 1 : 0;
	}
	
	private final int parseEpisodesPage(ListTask<Episode> task, Program program, Document document,
			boolean onlyFullEpisodes) throws Exception {
		// Always obtain the first page from the document's content
		Elements elItems = document.select(SEL_EPISODES);
		if(elItems != null
				&& parseEpisodeList(task, program, elItems, onlyFullEpisodes) == 2) {
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
					&& parseEpisodeList(task, program, elItems, onlyFullEpisodes) == 0);
		
		return 0;
	}
	
	private static final String mediaTitle(JSONCollection streamInfo, Document document) {
		// Markiza Plus has weird naming, so this is actually correct
		String programName = streamInfo.getString("episode", "");
		String episodeText = streamInfo.getString("programName", "");
		int numSeason = streamInfo.getInt("seasonNumber", 0);
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
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> {
			Document document = HTML.from(Net.uri(URL_PROGRAMS));
			
			for(Element elProgram : document.select(SEL_PROGRAMS)) {
				String programURL = elProgram.absUrl("href");
				String programTitle = elProgram.selectFirst("h3").text();
				Program program = new Program(Net.uri(programURL), programTitle);
				
				if(!task.add(program)) {
					return; // Do not continue
				}
			}
		});
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return ListTask.of((task) -> {
			for(String urlPath : List.of("videa/cele-epizody")) {
				URI uri = Net.uri(Net.uriConcat(program.uri().toString(), urlPath));
				
				Response.OfString response = Web.request(Request.of(uri).GET());
				if(response.statusCode() != 200) continue; // Probably does not exist, ignore
				
				Document document = HTML.parse(response.body(), uri);
				if(parseEpisodesPage(task, program, document, false) != 0) {
					return;
				}
			}
			
			// If no episodes were found, try to obtain them from the All videos page.
			if(task.isEmpty()) {
				URI uri = Net.uri(Net.uriConcat(program.uri().toString(), "videa"));
				Response.OfString response = Web.request(Request.of(uri).GET());
				
				if(response.statusCode() == 200) {
					Document document = HTML.parse(response.body(), uri);
					
					if(parseEpisodesPage(task, program, document, true) != 0) {
						return;
					}
				}
			}
		});
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			Document document = HTML.from(uri);
			Element iframe = document.selectFirst(SEL_PLAYER_IFRAME);
			
			if(iframe == null) {
				return; // Do not continue
			}
			
			String iframeUrl = iframe.absUrl("data-src");
			String content = Web.request(Request.of(Net.uri(iframeUrl)).GET()).body();
			
			if(content == null || content.isEmpty()) {
				return; // Do not continue
			}
			
			int begin = content.indexOf(TXT_PLAYER_CONFIG_BEGIN);
			
			// Video is not available, probably due to licensing issues
			if(begin < 0) {
				Translation tr = translation().getTranslation("error.media_unavailable");
				String message = HTML.parse(content).selectFirst(".b-player .e-title").text();
				Dialog.showContentInfo(tr.getSingle("title"), tr.getSingle("text"), message);
				return; // Do not continue
			}
			
			begin += TXT_PLAYER_CONFIG_BEGIN.length() - 1;
			String conScript = Utils.bracketSubstring(content, '{', '}', false, begin, content.length());
			
			if(!conScript.isEmpty()) {
				JSONCollection scriptData = JavaScript.readObject(conScript);
				
				if(scriptData != null) {
					JSONCollection tracks = scriptData.getCollection("tracks");
					URI sourceUri = uri;
					MediaSource source = MediaSource.of(this);
					String title = mediaTitle(scriptData.getCollection("plugins.measuring.streamInfo"), document);
					
					for(JSONCollection node : tracks.collectionsIterable()) {
						for(JSONCollection coll : ((JSONCollection) node).collectionsIterable()) {
							String videoUrl = coll.getString("src");
							MediaLanguage language = MediaLanguage.ofCode(coll.getString("lang"));
							
							List<Media> media = MediaUtils.createMedia(
								source, Net.uri(videoUrl), sourceUri, title, language, MediaMetadata.empty()
							);
							
							for(Media m : media) {
								if(!task.add(m)) {
									return; // Do not continue
								}
							}
						}
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
		if(!host.equals("markiza.sk") &&
		   !host.equals("videoarchiv.markiza.sk"))
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