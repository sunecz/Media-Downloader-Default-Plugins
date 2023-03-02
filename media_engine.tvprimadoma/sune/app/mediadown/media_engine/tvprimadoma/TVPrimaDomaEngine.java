package sune.app.mediadown.media_engine.tvprimadoma;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.MediaGetter;
import sune.app.mediadown.entity.MediaGetters;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.VideoMedia;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.JS;
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
	
	private final boolean parseEpisodesList(ListTask<Episode> task, Element elContainer, Program program)
			throws Exception {
		// All episodes are shown, just obtain them
		for(Element elEpisode : elContainer.select(SELECTOR_EPISODES)) {
			Element elTitle = elEpisode.selectFirst("h3");
			String url = elEpisode.absUrl("href");
			String title = elTitle.text();
			Episode episode = new Episode(program, Net.uri(url), title);
			
			if(!task.add(episode)) {
				return false; // Do not continue
			}
		}
		
		// All episodes successfully obtained
		return true;
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> {
			Document document = HTML.from(Net.uri(URL_PROGRAMS));
			
			for(Element elProgram : document.select(SELECTOR_PROGRAMS)) {
				Element elTitle = elProgram.selectFirst("h3");
				String url = elProgram.absUrl("href");
				String title = elTitle.text();
				Program program = new Program(Net.uri(url), title);
				
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
				if(!parseEpisodesList(task, elContainer, program)) {
					return;
				}
			}
			
			// Check whether all episodes are actually shown
			Element elPagination = document.selectFirst(SELECTOR_EPISODES_PAGINATION);
			if(elPagination != null) {
				final int maxPage = Integer.valueOf(elPagination.children().last().previousElementSibling().text());
				String urlBase = elPagination.selectFirst("[aria-current]").nextElementSibling().selectFirst("a")
					.absUrl("href").replaceFirst("\\?page=\\d+", "?page=%{page}d");
				
				for(int page = 2; page <= maxPage; ++page) {
					Document pageDocument = HTML.from(Net.uri(Utils.format(urlBase, "page", page)));
					Element elContainer = pageDocument.selectFirst(SELECTOR_EPISODES_CONTAINERS);
					
					if(!parseEpisodesList(task, elContainer, program)) {
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
			
			// The video can be embedded from Stream.cz
			Element elIframe = document.selectFirst(".container div > iframe");
			if(elIframe != null) {
				String iframeSrc = elIframe.absUrl("src");
				
				// The video can also be embedded from Stream.cz
				if(iframeSrc.startsWith("https://www.stream.cz")) {
					// Must transform the URL first to the canonical (one visited directly from Stream.cz)
					Document iframeDocument = HTML.from(Net.uri(iframeSrc));
					String canonicalUrl = iframeDocument.selectFirst("link[rel='canonical']").absUrl("href");
					URI canonicalUri = Net.uri(canonicalUrl);
					
					MediaGetter getter;
					if((getter = MediaGetters.fromURI(canonicalUri)) != null) {
						ListTask<Media> t = getter.getMedia(canonicalUri, Map.of());
						t.forwardAdd(task);
						t.startAndWait();
						return; // Sources obtained, do not continue
					}
				}
			}
			
			Element elScript = document.selectFirst(".container div > script");
			
			// Unable to obtain the embedding script
			if(elScript == null) {
				return;
			}
			
			String scriptSrc = elScript.absUrl("src");
			SSDCollection videoList = OnNetwork.videoList(scriptSrc);
			
			// Unable to obtain the player settings
			if(videoList == null) {
				return;
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
			URI sourceURI = uri;
			MediaLanguage language = MediaLanguage.UNKNOWN;
			MediaMetadata metadata = MediaMetadata.builder().title(title).build();
			
			// Add all available media that are found in the player settings
			SSDCollection emptyArray = SSDCollection.emptyArray();
			for(SSDCollection video : videoList.collectionsIterable()) {
				SSDCollection additionalURLs = video.getDirectCollection("urls", emptyArray);
				String videoURL = video.getDirectString("url");
				
				List<Media> media = new ArrayList<>();
				media.addAll(MediaUtils.createMedia(source, Net.uri(videoURL), sourceURI, title, language, metadata));
				
				for(SSDCollection additionalVideo : additionalURLs.collectionsIterable()) {
					String additionalVideoURL = additionalVideo.getDirectString("url");
					media.add(VideoMedia.simple().source(source)
					          	.uri(Net.uri(additionalVideoURL))
					          	.quality(MediaQuality.fromString(additionalVideo.getDirectString("name"), MediaType.VIDEO))
					          	.format(MediaFormat.fromPath(additionalVideoURL))
					          	.metadata(metadata)
					          	.build());
				}
				
				for(Media m : media) {
					if(!task.add(m)) {
						return; // Do not continue
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
			String script = Web.request(Request.of(Net.uri(url)).GET()).body();
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
			Map<String, List<String>> headers = Map.of("Referer", List.of(URL_REFERER));
			String content = Web.request(Request.of(Net.uri(frameURL)).headers(headers).GET()).body();
			
			SSDCollection playerVideos = null;
			if((index = content.indexOf("var playerVideos")) >= 0) {
				String data = Utils.bracketSubstring(content, '[', ']', false, index, content.length());
				playerVideos = JSON.read(data);
			}
			
			return playerVideos;
		}
	}
}