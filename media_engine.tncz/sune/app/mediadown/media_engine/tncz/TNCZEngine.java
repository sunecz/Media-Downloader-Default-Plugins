package sune.app.mediadown.media_engine.tncz;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

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
	private static final String SEL_PLAYER_IFRAME = "iframe[data-video-id]";
	private static final String TXT_PLAYER_CONFIG_BEGIN = "player:";
	
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
	
	// Allow to create an instance when registering the engine
	TNCZEngine() {
	}
	
	private static final Translation translation() {
		String path = "plugin." + PLUGIN.getContext().getPlugin().instance().name();
		return MediaDownloader.translation().getTranslation(path);
	}
	
	private static final String mediaTitle(JSONCollection streamInfo) {
		// TV Nova has weird naming, this is actually correct
		String programName = streamInfo.getString("episode", "");
		String episodeText = streamInfo.getString("programName", "");
		int numSeason = streamInfo.getInt("seasonNumber", 0);
		int numEpisode = -1;
		String episodeName = ""; // Use empty string rather than null
		
		Matcher matcher = REGEX_EPISODE.matcher(episodeText);
		if(matcher.matches()) {
			numEpisode = Integer.valueOf(matcher.group(1));
			episodeName = Optional.ofNullable(matcher.group(2)).orElse("");
		} else {
			// Some episodes names may contain the program name at the beginning, so we
			// remove it, if that's the case. Also, some additional characters, such as
			// spaces, commas, dashes, etc. right after it.
			if(episodeText.startsWith(programName)) {
				episodeName = episodeText.replaceFirst("^" + Regex.quote(programName) + "[^\\pL\\pN]*", "");
			} else {
				// Otherwise it should be OK to use it as an episode name
				episodeName = episodeText;
			}
		}
		
		return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
	}
	
	private final boolean parseEpisodeList(ListTask<Episode> task, Program program, Document document)
			throws Exception {
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
			
			Episode episode = new Episode(program, Net.uri(episodeURL), Utils.validateFileName(episodeName));
			
			if(!task.add(episode)) {
				return false; // Do not continue
			}
		}
		
		return true;
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> {
			Document document = HTML.from(Net.uri(URL_PROGRAMS));
			
			for(Element elProgram : document.select(SEL_PROGRAMS)) {
				String programURL = elProgram.absUrl("href");
				String programTitle = elProgram.selectFirst(".title").text();
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
			Document document = HTML.from(program.uri());
			
			// Always parse the episodes page itself
			if(!parseEpisodeList(task, program, document)) {
				return; // Do not continue
			}
			
			// Check for more episodes
			Element elButtonMore;
			if((elButtonMore = document.selectFirst(SEL_EPISODES_LOAD_MORE)) != null) {
				QueryArgument urlArgs = Net.queryDestruct(elButtonMore.attr("data-href"));
				
				int channel = Integer.valueOf(urlArgs.valueOf("channel"));
				int content = Integer.valueOf(urlArgs.valueOf("content"));
				String strFilter = urlArgs.valueOf("filter");
				
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
					
					document = HTML.from(Net.uri(url));
					
					if(!parseEpisodeList(task, program, document)) {
						return; // Do not continue
					}
					
					++page;
				} while(document.selectFirst(SEL_EPISODES_LOAD_MORE) != null);
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
			
			String iframeURL = iframe.absUrl("src");
			String content = Web.request(Request.of(Net.uri(iframeURL)).GET()).body();
			
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
			
			begin += TXT_PLAYER_CONFIG_BEGIN.length();
			String conScript = Utils.bracketSubstring(content, '{', '}', false, begin, content.length());
			
			if(!conScript.isEmpty()) {
				JSONCollection scriptData = JavaScript.readObject(conScript);
				
				if(scriptData != null) {
					JSONCollection tracks = scriptData.getCollection("lib.source.sources");
					URI sourceURI = uri;
					MediaSource source = MediaSource.of(this);
					String title = mediaTitle(scriptData.getCollection("plugins.events.customData"));
					
					for(JSONCollection node : tracks.collectionsIterable()) {
						String type = node.getString("type");
						MediaFormat format = MediaFormat.fromMimeType(type);
						String formatName = format.name().toLowerCase();
						String videoURL = node.getString("src");
						MediaLanguage language = MediaLanguage.UNKNOWN;
						MediaMetadata metadata = MediaMetadata.empty();
						
						if(node.hasCollection("contentProtection")) {
							JSONCollection drmInfo = node.getCollection("contentProtection");
							String drmToken = null;
							
							switch(formatName) {
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
							source, Net.uri(videoURL), sourceURI, title, language, metadata
						);
						
						for(Media s : media) {
							if(!task.add(s)) {
								return; // Do not continue
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