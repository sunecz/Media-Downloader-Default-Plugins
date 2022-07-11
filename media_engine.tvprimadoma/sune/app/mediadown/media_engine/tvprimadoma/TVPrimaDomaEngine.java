package sune.app.mediadown.media_engine.tvprimadoma;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Web.StringResponse;
import sune.app.mediadown.util.WorkerProxy;
import sune.app.mediadown.util.WorkerUpdatableTask;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDF;
import sune.util.ssdf2.SSDObject;
import sune.util.ssdf2.SSDType;

public final class TVPrimaDomaEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// URLs
	private static final String URL_PROGRAMS = "https://primadoma.tv/porady/";
	private static final String URL_REFERER = "https://primadoma.tv/";
	
	// Selectors
	private static final String SELECTOR_PROGRAMS = "main .boxed-show-box";
	private static final String SELECTOR_EPISODES_CONTAINERS = "main .boxed-video-comp";
	private static final String SELECTOR_EPISODES = ".boxed-video-item";
	private static final String SELECTOR_EPISODES_MORE = ".inline-more-link";
	private static final String SELECTOR_PAGINATION_ITEMS = "main .pagination-comp > a";
	private static final String SELECTOR_VIDEO_BREADCRUMB = "main .breadcrumbs-comp .breadcrumb-item";
	
	// RegExp
	private static final Pattern REGEX_STARTS_WITH_EPISODE_NUMBER;
	private static final Pattern REGEX_SEASON;
	private static final Pattern REGEX_EPISODE_AND_SEASON;
	
	static {
		REGEX_STARTS_WITH_EPISODE_NUMBER = Pattern.compile("(?i)^\\d+\\.? (díl|epizoda).*?$");
		REGEX_SEASON = Pattern.compile("(?i)^Sezóna (\\d+).*?$");
		REGEX_EPISODE_AND_SEASON = Pattern.compile("(?i)^((\\d+)\\.? díl(?:\\s*[,-]\\s+(?:série|sezóna) (\\d+))?[\\-\\s]*).*?$");
	}
	
	// Allow to create an instance when registering the engine
	TVPrimaDomaEngine() {
	}
	
	private final boolean parseEpisodesList(List<Episode> episodes, Element elContainer, String titlePrefix,
			Program program, WorkerProxy proxy, CheckedBiFunction<WorkerProxy, Episode, Boolean> function)
			throws Exception {
		String programTitle = program.title();
		Pattern regexProgramName = Pattern.compile("^" + Pattern.quote(programTitle) + "[\\-\\s]*");
		
		// All episodes are shown, just obtain them
		for(Element elEpisode : elContainer.select(SELECTOR_EPISODES)) {
			Element elTitle = elEpisode.selectFirst(".text-part h3 a");
			String url = elTitle.absUrl("href");
			String title = elTitle.text();
			
			// Prepend the title prefix, if any
			if(!titlePrefix.isEmpty()) {
				// Do not prepend if the title starts with episode number
				Matcher matcher = REGEX_STARTS_WITH_EPISODE_NUMBER.matcher(title);
				if(!matcher.matches()) title = titlePrefix + " - " + title;
			}
			
			// Remove program name from the episode name, if it is present
			if(title.startsWith(programTitle)) {
				Matcher matcher = regexProgramName.matcher(title);
				if(matcher.find()) title = title.substring(matcher.end(0));
			}
			
			Episode episode = new Episode(program, Utils.uri(url), title);
			episodes.add(episode);
			if(!function.apply(proxy, episode))
				return false; // Do not continue
		}
		// All episodes successfully obtained
		return true;
	}
	
	// ----- Internal methods
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	private final List<Program> internal_getPrograms() throws Exception {
		return internal_getPrograms(_dwp, (p, a) -> true);
	}
	
	private final List<Program> internal_getPrograms(WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
		List<Program> programs = new ArrayList<>();
		
		for(int page = 1;; ++page) {
			StringResponse response
				= Web.request(new GetRequest(Utils.url(URL_PROGRAMS + page), Shared.USER_AGENT, null, null, false));
			// When a page does not exist, it will redirect to homepage
			if(response.code != 200) break;
			
			Document document = Utils.parseDocument(response.content, response.url);
			for(Element elProgram : document.select(SELECTOR_PROGRAMS)) {
				Element elTitle = elProgram.selectFirst(".text-part h3 a");
				String url = elTitle.absUrl("href");
				String title = elTitle.text();
				Program program = new Program(Utils.uri(url), title);
				
				programs.add(program);
				if(!function.apply(proxy, program))
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
		for(Element elContainer : document.select(SELECTOR_EPISODES_CONTAINERS)) {
			String titlePrefix = "";
			
			// Obtain the title prefix, if any
			Element elContainerTitle = elContainer.previousElementSibling();
			if(elContainerTitle.tagName().equals("h2")) {
				String text = elContainerTitle.text();
				
				// Set the title prefix only if is not the program name
				if(!text.equalsIgnoreCase(program.title()))
					titlePrefix = text;
			}
			
			// Check whether all episodes are actually shown
			Element elLinkMore = elContainer.selectFirst(SELECTOR_EPISODES_MORE);
			if(elLinkMore != null) {
				String linkURL = elLinkMore.absUrl("href");
				int page = 1, maxPage = 0;
				
				do {
					String pageURL = linkURL + '/' + page;
					Document pageDocument = Utils.document(pageURL);
					
					// Obtain the last page number
					if(maxPage == 0) {
						Elements elPaginationItems = pageDocument.select(SELECTOR_PAGINATION_ITEMS);
						if(!elPaginationItems.isEmpty()) {
							Element elLast = elPaginationItems.get(elPaginationItems.size() - 1);
							maxPage = Integer.valueOf(Utils.urlBasename(elLast.attr("href")));
						}
					}
					
					Element elContainerPage = pageDocument.selectFirst(SELECTOR_EPISODES_CONTAINERS);
					if(!parseEpisodesList(episodes, elContainerPage, titlePrefix, program, proxy, function))
						return null;
				} while(++page <= maxPage);
			} else {
				if(!parseEpisodesList(episodes, elContainer, titlePrefix, program, proxy, function))
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
		Element elScript = document.selectFirst("header + * .active > script");
		
		// Unable to obtain the embedding script
		if(elScript == null)
			return null;
		
		String scriptSrc = elScript.absUrl("src");
		SSDCollection videoList = OnNetwork.videoList(url, scriptSrc, URL_REFERER);
		
		// Unable to obtain the player settings
		if(videoList == null)
			return null;
		
		// Gather as much information as possible and construct the title
		String programName = "";
		String numSeason = "";
		String numEpisode = "";
		String episodeName = "";
		
		// Parse the breadcrumb items for information
		int ctr = 0;
		for(Element elItem : document.select(SELECTOR_VIDEO_BREADCRUMB)) {
			// First item is just a homepage link
			if(ctr == 0) { ++ctr; continue; }
			String text = elItem.text();
			
			if(ctr == 1) {
				programName = text;
			} else if(ctr == 2) {
				// If it has a season format, mark it as a season,
				// otherwise use it as an episode name.
				Matcher matcher = REGEX_SEASON.matcher(text);
				if(matcher.matches()) numSeason = matcher.group(1);
				else episodeName = text;
			} else {
				if(!episodeName.isEmpty()) {
					// If the current text starts with an episode number,
					// clear the previous episode name contents.
					Matcher matcher = REGEX_STARTS_WITH_EPISODE_NUMBER.matcher(text);
					if(matcher.matches()) episodeName = "";
					else episodeName += " - ";
				}
				
				episodeName += text;
			}
			
			++ctr;
		}
		
		// Try to extract the episode and season number from the episode name
		if(numSeason.isEmpty() || numEpisode.isEmpty()) {
			Matcher matcher = REGEX_EPISODE_AND_SEASON.matcher(episodeName);
			if(matcher.matches()) {
				// Set the matched episode and season, if needed
				if(numEpisode.isEmpty()) numEpisode = matcher.group(2);
				if(numSeason .isEmpty()) numSeason  = matcher.group(3);
				
				// Remove the matched text from the episode name
				episodeName = episodeName.substring(matcher.end(1));
			}
		}
		
		// Remove program name from the episode name, if it is present
		Pattern regexProgramName = Pattern.compile("^" + Pattern.quote(programName) + "[\\-\\s]*");
		Matcher matcher = regexProgramName.matcher(episodeName);
		if(matcher.find()) episodeName = episodeName.substring(matcher.end(0));
		
		// Clean up, if some information is missing
		if(numSeason.isEmpty()) numSeason = null;
		if(numEpisode.isEmpty()) numEpisode = null;
		
		String title = MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName, true, false, false);
		MediaSource source = MediaSource.of(this);
		URI sourceURI = Utils.uri(url);
		MediaLanguage language = MediaLanguage.UNKNOWN;
		MediaMetadata metadata = MediaMetadata.builder().title(title).build();
		
		// Add all available media that are found in the player settings
		SSDCollection emptyArray = SSDCollection.emptyArray();
		for(SSDCollection video : videoList.collectionsIterable()) {
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
				if(!function.apply(proxy, m))
					return null; // Do not continue
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
		if(!host.equals("primadoma.tv"))
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
	
	private static final class OnNetwork {
		
		// Forbid anyone to create an instance of this class
		private OnNetwork() {
		}
		
		public static final SSDCollection videoList(String url, String scriptSrc, String referer) throws Exception {
			String frameURL = null;
			String script = Web.request(new GetRequest(Utils.url(scriptSrc), Shared.USER_AGENT)).content;
			int index;
			if((index = script.indexOf("'https://video.onnetwork.tv/")) >= 0) {
				frameURL = script.substring(index, script.indexOf(';', index)).trim();
			}
			
			// Unable to obtain the frame URL "template"
			if(frameURL == null)
				return null;
			
			SSDCollection playerData = null;
			if((index = script.indexOf("_NPlayer=")) >= 0) {
				String data = Utils.bracketSubstring(script, '{', '}', false, index, script.length());
				data = data.replaceAll("([\"'])\\+window\\.ONTVplayerNb", "0$1");
				playerData = SSDF.read(data);
			}
			
			// Unable to obtain the mandatory player data
			if(playerData == null)
				return null;
			
			SSDCollection pd = playerData;
			frameURL = Utils.replaceAll("[\"']?\\+([^\\+]+)\\+[\"']?", frameURL, (matcher) -> {
				String name = matcher.group(1).replaceFirst("Player\\.", "");
				return pd.getDirectObject(name).stringValue();
			});
			frameURL = Utils.unquote(frameURL);
			
			SSDCollection fields = null;
			if((index = script.indexOf("ONTVFields=")) >= 0) {
				String data = Utils.bracketSubstring(script, '{', '}', false, index, script.length());
				fields = SSDF.read(data);
			}
			
			// Unable to obtain the mandatory player fields
			if(fields == null)
				return null;
			
			// Directly translated from the JavaScript code
			SSDObject empty = SSDObject.ofRaw("", "");
			for(SSDCollection field : fields.collectionsIterable()) {
				SSDObject fieldObject, playerField;
				if(!(fieldObject = field.getDirectObject("n", empty)).stringValue().isEmpty()
						&& (playerField = playerData.getDirectObject(field.getName(), empty)) != null) {
					if(field.getDirectObject("p", empty).stringValue().equals("gz")) {
						if(playerField.getType() == SSDType.INTEGER && playerField.intValue() > 0) {
							frameURL += '&' + fieldObject.stringValue() + '=' + playerField.stringValue();
						}
					} else if(field.getDirectObject("p", empty).stringValue().equals("gez")) {
						if(playerField.getType() == SSDType.INTEGER && playerField.intValue() >= 0) {
							frameURL += '&' + fieldObject.stringValue() + '=' + playerField.stringValue();
						}
					} else if(field.getDirectObject("p", empty).stringValue().equals("ne")) {
						if(!playerField.stringValue().isEmpty()) {
							frameURL += '&' + fieldObject.stringValue() + '=' + JavaScript.encodeURIComponent(playerField.stringValue());
						}
					}
				}
			}
			
			// Also append some additional information to mimic a correct frame URL
			frameURL += "&wtop=" + url + "&apop=0&vpop=0&apopa=0&vpopa=0";
			
			// Send the request to obtain the frame's content
			Map<String, String> headers = Map.of("Referer", referer);
			String content = Web.request(new GetRequest(Utils.url(frameURL), Shared.USER_AGENT, headers)).content;
			
			SSDCollection videoList = null;
			if((index = content.indexOf("tUIPlayer")) >= 0) {
				String data = Utils.bracketSubstring(content, '{', '}', false, index, content.length());
				
				// Since some settings can cause errors due to JavaScript code being present,
				// obtain just the video list.
				if((index = data.indexOf("videos")) >= 0) {
					data = Utils.bracketSubstring(data, '[', ']', false, index, data.length());
				}
				
				videoList = SSDF.read(data);
			}
			
			return videoList;
		}
	}
}