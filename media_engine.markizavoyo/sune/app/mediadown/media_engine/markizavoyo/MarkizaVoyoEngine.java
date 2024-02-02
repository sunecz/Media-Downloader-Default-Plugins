package sune.app.mediadown.media_engine.markizavoyo;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javafx.scene.image.Image;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.Shared;
import sune.app.mediadown.authentication.CredentialsManager;
import sune.app.mediadown.authentication.EmailCredentials;
import sune.app.mediadown.concurrent.VarLoader;
import sune.app.mediadown.configuration.Configuration;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.exception.IncorrectCredentials;
import sune.app.mediadown.exception.MissingCredentials;
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
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

public final class MarkizaVoyoEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Allow to create an instance when registering the engine
	MarkizaVoyoEngine() {
	}
	
	private static final Translation translation() {
		String path = "plugin." + PLUGIN.getContext().getPlugin().instance().name();
		return MediaDownloader.translation().getTranslation(path);
	}
	
	private static final PluginConfiguration configuration() {
		return PLUGIN.getContext().getConfiguration();
	}
	
	private static final String credentialsName() {
		return "plugin/" + PLUGIN.getContext().getPlugin().instance().name().replace('.', '/');
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return API.getPrograms(new API.Sort(API.Sort.By.TITLE, API.Sort.Order.ASC));
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return API.getEpisodes(program);
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return API.getMedia(this, uri, data);
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
		if(!host.equals("voyo.markiza.sk"))
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
	
	private static final class API {
		
		private static final URI URI_ENDPOINT = Net.uri("https://voyo.markiza.sk/api/v1/");
		
		private static final Category TV_SHOWS = new Category("voyo-4", 17);
		private static final Category TV_SERIES = new Category("voyo-3", 16);
		private static final Category MOVIES = new Category("voyo-5", 18);
		private static final Category KIDS = new Category("voyo-7", 20);
		
		private static final Category[] CATEGORIES = {
			TV_SHOWS, TV_SERIES, MOVIES, KIDS
		};
		
		private static final int ITEMS_PER_PAGE = 64;
		
		private static final int RESULT_SUCCESS = 0;
		private static final int RESULT_NO_MORE_ITEMS = 1;
		private static final int RESULT_CANCEL = 2;
		
		private static final Regex REGEX_EPISODE = Regex.of("^(?:.*?(?: - |: ))?(\\d+)\\. díl(?:(?: - |: )(.*))?$");
		
		private static final Response.OfStream request(String action, Map<String, Object> args) throws Exception {
			URI uri = URI_ENDPOINT.resolve(action + '?' + Net.queryString(args));
			Request request = Request.of(uri)
				.addHeaders("Referer", "https://voyo.markiza.sk/", "X-Requested-With", "XMLHttpRequest")
				.GET();
			return Web.requestStream(request);
		}
		
		private static final int parsePrograms(ListTask<Program> task, Response.OfStream response)
				throws Exception {
			// The JavaScript object filteredShowDataX does not always exist (e.g. for the first page),
			// therefore just use the HTML content of the page.
			Document document = HTML.parse(new String(response.stream().readAllBytes(), Shared.CHARSET), response.uri());
			
			for(Element elWrapper : document.select(".row > .i")) {
				Element elLink = elWrapper.selectFirst(".title > a");
				String programId = elWrapper.selectFirst(".c-video-box").attr("data-resource").replace("show.", "");
				URI url = Net.uri(elLink.absUrl("href"));
				String title = elLink.text().trim();
				Program program = new Program(url, title, "programId", programId);
				
				if(!task.add(program)) {
					return RESULT_CANCEL; // Do not continue
				}
			}
			
			Element elNav = document.selectFirst(".c-pagination");
			
			if(elNav == null) {
				// No pagination, meaning no more items
				return RESULT_NO_MORE_ITEMS;
			}
			
			Element elItemContent = elNav.selectFirst("li:last-child > *");
			
			if(elItemContent.hasClass("-disabled")) {
				// The last list item contains either an anchor element, having a link
				// to the next page, which is available, or a span element, which indicates
				// that there is no more items, i.e. we are on the last page.
				return RESULT_NO_MORE_ITEMS;
			}
			
			return RESULT_SUCCESS;
		}
		
		private static final int listPrograms(ListTask<Program> task, Category category, Sort sort, int page, int count)
				throws Exception {
			String action = "shows/genres";
			Map<String, Object> args = Map.of(
				"category", category.categoryId(),
				"pageId", category.pageId(),
				"sort", sort.internalName(),
				"limit", count,
				"page", page
			);
			
			try(Response.OfStream response = request(action, args)) {
				return parsePrograms(task, response);
			}
		}
		
		private static final boolean loopListPrograms(ListTask<Program> task, Category category, Sort sort) throws Exception {
			loop:
			for(int page = 1;; ++page) {
				switch(listPrograms(task, category, sort, page, ITEMS_PER_PAGE)) {
					case RESULT_CANCEL:
						return false; // Do not continue
					case RESULT_NO_MORE_ITEMS:
						break loop; // End the loop
					default:
						continue; // Continue to the next page
				}
			}
			
			return true;
		}
		
		private static final int parseEpisodes(ListTask<Episode> task, Program program, Season season,
				Response.OfStream response, int offset) throws Exception {
			String content = new String(response.stream().readAllBytes(), Shared.CHARSET);
			
			if(content.isEmpty()) {
				// Nothing to be shown, therefore no more items
				return RESULT_NO_MORE_ITEMS;
			}
			
			Document document = HTML.parse(content, response.uri());
			Elements items = document.select("article");
			int counter = offset + 1;
			
			for(Element elWrapper : items) {
				Element elLink = elWrapper.selectFirst(".title > a");
				URI url = Net.uri(elLink.absUrl("href"));
				String title = elLink.text().trim();
				int numEpisode = counter++;
				int numSeason = season.number();
				
				if(title.equalsIgnoreCase(numEpisode + ". díl")) {
					title = "";
				}
				
				Episode episode = new Episode(program, url, title, numEpisode, numSeason);
				
				if(!task.add(episode)) {
					return RESULT_CANCEL; // Do not continue
				}
				
			}
			
			Element elLoadMore = document.selectFirst(".load-more");
			
			if(elLoadMore == null) {
				// No Load more button, meaning no more items
				return RESULT_NO_MORE_ITEMS;
			}
			
			return RESULT_SUCCESS;
		}
		
		private static final int listEpisodes(ListTask<Episode> task, Program program, Season season, int offset,
				int count) throws Exception {
			String action = "show/content";
			Map<String, Object> args = Map.of(
				"showId", program.get("programId"),
				"type", "episodes",
				"season", season.id(),
				"orderDirection", Sort.Order.ASC.internalName(), // Works only with ASC order
				"offset", offset,
				"count", count,
				"url", program.uri().getPath()
			);
			
			try(Response.OfStream response = request(action, args)) {
				return parseEpisodes(task, program, season, response, offset);
			}
		}
		
		private static final boolean loopListEpisodes(ListTask<Episode> task, Program program, Season season)
				throws Exception {
			loop:
			for(int offset = 0, count = ITEMS_PER_PAGE;; offset += count) {
				switch(listEpisodes(task, program, season, offset, count)) {
					case RESULT_CANCEL:
						return false; // Do not continue
					case RESULT_NO_MORE_ITEMS:
						break loop; // End the loop
					default:
						continue; // Continue to the next page
				}
			}
			
			return true;
		}
		
		private static final VoyoError checkForError(Document document) throws Exception {
			if(!document.body().hasClass("error")) {
				return VoyoError.ofSuccess();
			}
			
			for(Element elScript : document.select("script:not([src])")) {
				String content = elScript.html();
				
				int index;
				if((index = content.indexOf("klebetnica(")) >= 0) {
					String object = Utils.bracketSubstring(content, '{', '}', false, index, content.length());
					object = object.replace("'", "\""); // JSON reader supports only double quotes
					JSONCollection data = JSON.newReader(object).allowUnquotedNames(true).read();
					return VoyoError.ofFailure(data);
				}
			}
			
			return VoyoError.ofFailure(null);
		}
		
		private static final String mediaTitle(JSONCollection streamInfo, Document document) {
			// Nova Voyo has weird naming, this is actually correct
			String programName = streamInfo.getString("episode", "");
			int numSeason = -1;
			int numEpisode = -1;
			String episodeName = null;
			
			if(programName.isEmpty()) {
				// No useful information can be extracted from streamInfo, use LinkingData
				LinkingData linkingData = LinkingData.from(document).stream()
					.filter((ld) -> Utils.contains(Set.of("Movie", "TVEpisode"), ld.type()))
					.findFirst().orElseGet(LinkingData::empty);
				
				if(!linkingData.isEmpty()) {
					programName = linkingData.data().getString("name", "");
				}
			} else {
				String episodeText = streamInfo.getString("programName", "");
				numSeason = streamInfo.getInt("seasonNumber", 0);
				
				Matcher matcher = REGEX_EPISODE.matcher(episodeText);
				if(matcher.matches()) {
					numEpisode = Integer.parseInt(matcher.group(1));
					episodeName = Optional.ofNullable(matcher.group(2)).orElse("");
				} else {
					episodeName = episodeText;
				}
			}
			
			return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
		}
		
		private static final Document getProgramDetail(Program program) throws Exception {
			String action = "page/detail-url";
			Map<String, Object> args = Map.of(
				"layout_parts[]", "40-10",
				"url", program.uri().toString()
			);
			
			try(Response.OfStream response = request(action, args)) {
				String content = new String(response.stream().readAllBytes(), Shared.CHARSET);
				content = content.replace("\\n", "\n").replace("\\\"", "\\\\\\\""); // Resolve escaped characters
				JSONCollection json = JSON.read(content);
				
				if(json.hasString("data.redirect.url")) {
					URI uri = Net.uri(json.getString("data.redirect.url"));
					return HTML.from(Request.of(uri).GET());
				}
				
				return HTML.parse(json.getString("data.content.40-10").replace("\\\"", "\""));
			}
		}
		
		private static final List<Season> getSeasons(Document detail) throws Exception {
			List<Season> seasons = new ArrayList<>();
			
			Element dropdownMenu = detail.selectFirst("#episodesDropdown + .dropdown-menu");
			Elements elItems = dropdownMenu.select(".dropdown-item");
			
			for(Element elItem : elItems) {
				String id = elItem.attr("data-season-id");
				// Since some programs have seasons in the ascending order and
				// some have them in the descending order, we must extract the number
				// of a season from the text itself.
				int number = Utils.extractInt(elItem.textNodes().get(0).text(), 0);
				seasons.add(new Season(id, number));
			}
			
			return seasons;
		}
		
		private static final void displayError(VoyoError error) {
			Translation tr = translation().getTranslation("error");
			String message = tr.getSingle("value." + error.type());
			tr = tr.getTranslation("media_error");
			Dialog.showContentInfo(tr.getSingle("title", "name", error.event()), tr.getSingle("text"), message);
		}
		
		public static final ListTask<Program> getPrograms(Sort sort) throws Exception {
			return ListTask.of((task) -> {
				for(Category category : CATEGORIES) {
					if(!loopListPrograms(task, category, sort)) {
						return; // Do not continue
					}
				}
			});
		}
		
		public static final ListTask<Episode> getEpisodes(Program program) throws Exception {
			return ListTask.of((task) -> {
				Document detail = getProgramDetail(program);
				
				if(detail.selectFirst(".listing") == null) { // Movie
					URI uri = program.uri().resolve("#player-fullscreen");
					String title = detail.selectFirst("h1.title").text().trim();
					Episode episode = new Episode(program, uri, title);
					
					task.add(episode);
					return; // Do not continue
				}
				
				for(Season season : getSeasons(detail)) {
					if(!loopListEpisodes(task, program, season)) {
						return; // Do not continue
					}
				}
			});
		}
		
		public static final ListTask<Media> getMedia(MediaEngine engine, URI uri, Map<String, Object> data)
				throws Exception {
			return ListTask.of((task) -> {
				// Obtain the main iframe for the video
				Document document = HTML.from(uri);
				Element elIframe = document.selectFirst(".js-detail-player .iframe-wrap iframe");
				
				if(elIframe == null) {
					return;
				}
				
				URI embedUri = Net.uri(elIframe.absUrl("src"));
				Request request;
				Document embedDoc;
				HttpCookie authCookie;
				int attempt = 0;
				
				loop:
				do {
					authCookie = VoyoAccount.authCookie();
					request = Request.of(embedUri).addCookie(authCookie).GET();
					embedDoc = HTML.from(request);
					
					VoyoError error;
					if((error = checkForError(embedDoc)).isSuccess()) {
						break;
					}
					
					switch(error.type()) {
						case "player_not_logged_in": {
							// Only try a new token when this is the first attempt
							if(attempt++ == 0) {
								VoyoAccount.resetAuthCookie();
								break;
							}
							
							displayError(error);
							return; // Do not continue
						}
						case "player_parental_profile_age_required": {
							if(VoyoAccount.bypassAgeRestriction()) {
								embedDoc = HTML.from(request); // Retry
								
								if(checkForError(embedDoc).isSuccess()) {
									break loop;
								}
							}
							
							displayError(error);
							return; // Do not continue
						}
						default: {
							displayError(error);
							return; // Do not continue
						}
					}
				} while(attempt <= 1);
				
				JSONCollection settings = null;
				for(Element elScript : embedDoc.select("script:not([src])")) {
					String content = elScript.html();
					int index;
					if((index = content.indexOf("Player.init")) >= 0) {
						content = Utils.bracketSubstring(content, '{', '}', false, index, content.length());
						settings = JavaScript.readObject(content);
						break;
					}
				}
				
				if(settings == null)
					return; // Do not continue
				
				JSONCollection tracks = settings.getCollection("tracks");
				URI sourceURI = uri;
				MediaSource source = MediaSource.of(engine);
				
				String title = mediaTitle(settings.getCollection("plugins.measuring.streamInfo"), document);
				for(JSONCollection node : tracks.collectionsIterable()) {
					MediaFormat format = MediaFormat.fromName(node.name());
					String formatName = node.name().toLowerCase();
					
					for(JSONCollection coll : ((JSONCollection) node).collectionsIterable()) {
						String videoURL = coll.getString("src");
						MediaLanguage language = MediaLanguage.ofCode(coll.getString("lang"));
						MediaMetadata metadata = MediaMetadata.empty();
						
						if(format == MediaFormat.UNKNOWN) {
							format = MediaFormat.fromPath(videoURL);
						}
						
						if(coll.hasCollection("drm")) {
							JSONCollection drmInfo = coll.getCollection("drm");
							String drmToken = null;
							
							switch(formatName) {
								case "dash":
									drmToken = Utils.stream(drmInfo.collectionsIterable())
										.filter((c) -> c.getString("keySystem").equals("com.widevine.alpha"))
										.flatMap((c) -> Utils.stream(c.getCollection("headers").collectionsIterable()))
										.filter((h) -> h.getString("name").equals("X-AxDRM-Message"))
										.map((h) -> h.getString("value"))
										.findFirst().orElse(null);
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
			});
		}
		
		private static final class Sort {
			
			private final By by;
			private final Order order;
			
			public Sort(By by, Order order) {
				this.by = Objects.requireNonNull(by);
				this.order = Objects.requireNonNull(order);
			}
			
			public String internalName() {
				return String.format("%s__%s", by.internalName(), order.internalName());
			}
			
			public static enum By {
				
				TITLE("title"), DATE("date");
				
				private final String internalName;
				
				private By(String internalName) {
					this.internalName = internalName;
				}
				
				public String internalName() { return internalName; }
			}
			
			public static enum Order {
				
				ASC("asc"), DESC("desc");
				
				private final String internalName;
				
				private Order(String internalName) {
					this.internalName = internalName;
				}
				
				public String internalName() { return internalName; }
			}
		}
		
		private static final class Category {
			
			private final String categoryId;
			private final int pageId;
			
			private Category(String categoryId, int pageId) {
				this.categoryId = categoryId;
				this.pageId = pageId;
			}
			
			public String categoryId() { return categoryId; }
			public int pageId() { return pageId; }
		}
		
		private static final class Season {
			
			private final String id;
			private final int number;
			
			public Season(String id, int number) {
				this.id = id;
				this.number = number;
			}
			
			public String id() { return id; }
			public int number() { return number; }
		}
	}
	
	private static final class VoyoAccount {
		
		private static final URI URI_AGE_RESTRICTION;
		
		private static VarLoader<HttpCookie> deviceCookie = VarLoader.ofChecked(VoyoAccount::loadDeviceCookie);
		
		static {
			URI_AGE_RESTRICTION = Net.uri("https://voyo.markiza.sk/obrazovky-prehravaca/rodicovska-kontrola-profil");
		}
		
		// Forbid anyone to create an instance of this class
		private VoyoAccount() {
		}
		
		private static final Path configurationPath() {
			return NIO.localPath("resources/config/" + PLUGIN.getContext().getPlugin().instance().name() + ".ssdf");
		}
		
		private static final String deviceToken() {
			String token = configuration().stringValue("token");
			return token == null || token.isEmpty() ? null : token; // Normalize
		}
		
		private static final void saveDeviceToken(String token) throws IOException {
			Configuration.Writer writer = configuration().writer();
			writer.set("token", token);
			writer.save(configurationPath());
		}
		
		private static final void removeDeviceToken() throws IOException {
			saveDeviceToken(""); // Must use empty, not null
		}
		
		private static final String loadDeviceToken() throws Exception {
			String deviceToken = deviceToken();
			
			if(deviceToken != null) {
				return deviceToken; // Use the saved device token
			}
			
			if(!Authenticator.areLoginDetailsPresent()) {
				throw new MissingCredentials();
			}
			
			if(!Authenticator.login()) {
				throw new IncorrectCredentials();
			}
			
			for(HttpCookie hc : Web.cookieManager().getCookieStore().getCookies()) {
				if(!hc.getName().equals("votoken")) {
					continue;
				}
				
				deviceToken = hc.getValue();
				break; // No need to continue
			}
			
			if(deviceToken != null) {
				saveDeviceToken(deviceToken); // Remember the token
			}
			
			return deviceToken;
		}
		
		private static final HttpCookie loadDeviceCookie() throws Exception {
			String token = loadDeviceToken();
			
			if(token == null) {
				throw new IllegalStateException("Invalid device token");
			}
			
			HttpCookie cookie = new HttpCookie("votoken", token);
			cookie.setDomain(".markiza.sk");
			cookie.setPath("/");
			cookie.setSecure(true);
			cookie.setHttpOnly(true);
			cookie.setMaxAge(315360000L); // 10 years in seconds
			
			return cookie;
		}
		
		private static final HttpCookie deviceCookie() throws Exception {
			return deviceCookie.valueChecked();
		}
		
		public static final HttpCookie authCookie() throws Exception {
			return deviceCookie();
		}
		
		public static final void resetAuthCookie() throws Exception {
			removeDeviceToken(); // Discard saved token
			Web.cookieManager().getCookieStore().removeAll(); // Clear session
			deviceCookie = VarLoader.ofChecked(VoyoAccount::loadDeviceCookie);
		}
		
		public static boolean bypassAgeRestriction() throws Exception {
			String body = Net.queryString(Map.of(
				"birth_day", 1,
				"birth_month", 1,
				"birth_year", 2000,
				"save", "Uložiť",
				"_do", "content232-userAgeForm-form-submit"
			));
			
			try(Response.OfStream response = Web.requestStream(Request.of(URI_AGE_RESTRICTION).POST(body))) {
				return response.statusCode() == 200;
			}
		}
	}
	
	private static final class Authenticator {
		
		private static final String URL_LOGIN = "https://voyo.markiza.sk/prihlasenie";
		private static final String URL_REDIRECT_LOGIN = "https://voyo.markiza.sk/moj-profil";
		
		// Forbid anyone to create an instance of this class
		private Authenticator() {
		}
		
		private static final EmailCredentials credentials() throws IOException {
			return (EmailCredentials) CredentialsManager.instance().get(credentialsName());
		}
		
		private static final boolean login(String username, String password) throws Exception {
			if(username.isEmpty() || password.isEmpty()) {
				return false;
			}
			
			URI uri = Net.uri(URL_LOGIN);
			Document document = HTML.from(uri);
			String argDo = document.selectFirst("input[type='hidden'][name='_do']").val();
			
			HttpHeaders headers = Web.Headers.ofSingle(
	 			"Referer", URL_LOGIN
	 		);
			
			Map<String, Object> args = Map.of(
	            "email", username,
	            "password", password,
	            "login", "Prihlásiť",
	            "_do", argDo
	        );
			
			String body = Net.queryString(args);
			Response.OfString response = Web.request(Request.of(uri).headers(headers).POST(body));
			return response.uri().toString().equals(URL_REDIRECT_LOGIN);
		}
		
		public static final boolean login() throws Exception {
			try(EmailCredentials credentials = credentials()) {
				return login(credentials.email(), credentials.password());
			}
		}
		
		public static final boolean areLoginDetailsPresent() throws Exception {
			try(EmailCredentials credentials = credentials()) {
				return Utils.OfString.nonEmpty(credentials.email()) && Utils.OfString.nonEmpty(credentials.password());
			}
		}
	}
	
	private static final class VoyoError {
		
		private final boolean isSuccess;
		private final String event;
		private final String type;
		
		private VoyoError(boolean isSuccess, JSONCollection data) {
			this.isSuccess = isSuccess;
			
			String event = null;
			String type = null;
			
			if(data != null) {
				event = data.getString("event", "");
				type = data.getString("data.type", "");
			}
			
			this.event = event;
			this.type = type;
		}
		
		public static final VoyoError ofSuccess() {
			return new VoyoError(true, null);
		}
		
		public static final VoyoError ofFailure(JSONCollection data) {
			return new VoyoError(false, data);
		}
		
		public boolean isSuccess() {
			return isSuccess;
		}
		
		public String event() {
			return event;
		}
		
		public String type() {
			return type;
		}
	}
	
	private static final class LinkingData {
		
		private static LinkingData EMPTY;
		
		private final Document document;
		private final JSONCollection data;
		private final String type;
		
		private LinkingData() {
			this.document = null;
			this.data = null;
			this.type = "none";
		}
		
		private LinkingData(Document document, JSONCollection data) {
			this.document = Objects.requireNonNull(document);
			this.data = Objects.requireNonNull(data);
			this.type = data.getString("@type");
		}
		
		public static final LinkingData empty() {
			return EMPTY == null ? (EMPTY = new LinkingData()) : EMPTY;
		}
		
		public static final List<LinkingData> from(Document document) {
			return document.select("script[type='application/ld+json']")
						.stream()
						.map((s) -> new LinkingData(document, JSON.read(s.html())))
						.collect(Collectors.toList());
		}
		
		public final boolean isEmpty() {
			return data == null;
		}
		
		@SuppressWarnings("unused")
		public final Document document() {
			return document;
		}
		
		public final JSONCollection data() {
			return data;
		}
		
		public final String type() {
			return type;
		}
	}
}