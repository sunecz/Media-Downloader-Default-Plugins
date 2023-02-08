package sune.app.mediadown.media_engine.tvprimadoma;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.Episode;
import sune.app.mediadown.MediaGetter;
import sune.app.mediadown.MediaGetters;
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
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.JS;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.WorkerProxy;
import sune.app.mediadown.util.WorkerUpdatableTask;
import sune.util.ssdf2.SSDCollection;

public final class TVPrimaDomaEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// URLs
	private static final String URL_PROGRAMS = "https://primadoma.tv/porady";
	private static final String URL_REFERER = "https://primadoma.tv/";
	
	// Selectors
	private static final String SELECTOR_PROGRAMS = ".container .row .col > article > a";
	private static final String SELECTOR_EPISODES_CONTAINERS = ".head + section > .container > .row";
	private static final String SELECTOR_EPISODES = ".col > article > a";
	private static final String SELECTOR_EPISODES_PAGINATION = ".pagination-desktop";
	
	// Allow to create an instance when registering the engine
	TVPrimaDomaEngine() {
	}
	
	private final boolean parseEpisodesList(List<Episode> episodes, Element elContainer, Program program,
			WorkerProxy proxy, CheckedBiFunction<WorkerProxy, Episode, Boolean> function)
			throws Exception {
		// All episodes are shown, just obtain them
		for(Element elEpisode : elContainer.select(SELECTOR_EPISODES)) {
			Element elTitle = elEpisode.selectFirst("h3");
			String url = elEpisode.absUrl("href");
			String title = elTitle.text();
			Episode episode = new Episode(program, Utils.uri(url), title);
			
			episodes.add(episode);
			if(!function.apply(proxy, episode)) {
				return false; // Do not continue
			}
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
		Document document = Utils.document(Utils.url(URL_PROGRAMS));
		
		for(Element elProgram : document.select(SELECTOR_PROGRAMS)) {
			Element elTitle = elProgram.selectFirst("h3");
			String url = elProgram.absUrl("href");
			String title = elTitle.text();
			Program program = new Program(Utils.uri(url), title);
			
			programs.add(program);
			if(!function.apply(proxy, program))
				return null; // Do not continue
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
			Element elContainerTitle = elContainer.selectFirst("h2");
			
			if(elContainerTitle != null) {
				String text = elContainerTitle.text();
				
				// Ignore the most watched videos, since they will be present
				// somewhere in the list anyway.
				if(text.toLowerCase().contains("nejsledovanější")) {
					continue;
				}
			}
			
			// Always parse the existing items
			if(!parseEpisodesList(episodes, elContainer, program, proxy, function)) {
				return null;
			}
		}
		
		// Check whether all episodes are actually shown
		Element elPagination = document.selectFirst(SELECTOR_EPISODES_PAGINATION);
		if(elPagination != null) {
			final int maxPage = Integer.valueOf(elPagination.children().last().previousElementSibling().text());
			String urlBase = elPagination.selectFirst("[aria-current]").nextElementSibling().selectFirst("a")
				.absUrl("href").replaceFirst("\\?page=\\d+", "?page=%{page}d");
			
			for(int page = 2; page <= maxPage; ++page) {
				Document pageDocument = Utils.document(Utils.format(urlBase, "page", page));
				Element elContainer = pageDocument.selectFirst(SELECTOR_EPISODES_CONTAINERS);
				
				if(!parseEpisodesList(episodes, elContainer, program, proxy, function)) {
					return null;
				}
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
		
		// The video can be embedded from Stream.cz
		Element elIframe = document.selectFirst(".container div > iframe");
		if(elIframe != null) {
			String iframeSrc = elIframe.absUrl("src");
			
			// The video can also be embedded from Stream.cz
			if(iframeSrc.startsWith("https://www.stream.cz")) {
				// Must transform the URL first to the canonical (one visited directly from Stream.cz)
				Document iframeDocument = Utils.document(iframeSrc);
				String canonicalUrl = iframeDocument.selectFirst("link[rel='canonical']").absUrl("href");
				
				MediaGetter getter;
				if((getter = MediaGetters.fromURL(canonicalUrl)) != null) {
					WorkerUpdatableTask<CheckedBiFunction<WorkerProxy, Media, Boolean>, Void> task
						= getter.getMedia(Utils.uri(canonicalUrl), Map.of(),
						                  (p, media) -> sources.add(media) && function.apply(p, media));
					
					// Run the task. Must be canceled afterwards, otherwise it will run in the background.
					try { task.startAndWaitChecked(); } finally { task.cancel(); }
					
					return sources; // Sources obtained, do not continue
				}
			}
		}
		
		Element elScript = document.selectFirst(".container div > script");
		
		// Unable to obtain the embedding script
		if(elScript == null) {
			return null;
		}
		
		String scriptSrc = elScript.absUrl("src");
		SSDCollection videoList = OnNetwork.videoList(scriptSrc);
		
		// Unable to obtain the player settings
		if(videoList == null) {
			return null;
		}
		
		// Gather as much information as possible and construct the title
		String programName = "";
		String numSeason = null;
		String numEpisode = null;
		String episodeName = "";
		
		// Obtain the program name from logos, since there is no other stable
		// place where it can be found.
		Element elLogo = document.selectFirst(".container > .row > .col > div + div + .d-md-flex > div:first-child > a");
		if(elLogo != null) {
			programName = elLogo.attr("title");
		}
		
		// Obtain the episode name from the episode title
		Element elTitle = document.selectFirst(".container > .row > .col > div + div h1");
		if(elTitle != null) {
			episodeName = elTitle.text();
		}
		
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
		
		public static final SSDCollection videoList(String url) throws Exception {
			String frameURL = null;
			String script = Web.request(new GetRequest(Utils.url(url), Shared.USER_AGENT)).content;
			int index;
			if((index = script.indexOf("{\"")) >= 0) {
				String configContent = Utils.bracketSubstring(script, '{', '}', false, index, script.length());
				SSDCollection config = JavaScript.readObject(configContent);
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
			
			return playerVideos;
		}
	}
}