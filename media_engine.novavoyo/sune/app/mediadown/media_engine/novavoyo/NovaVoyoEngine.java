package sune.app.mediadown.media_engine.novavoyo;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import javafx.scene.image.Image;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.Shared;
import sune.app.mediadown.configuration.Configuration;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
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
import sune.app.mediadown.util.Utils.Ignore;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDF;
import sune.util.ssdf2.SSDObject;

public final class NovaVoyoEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	private static final Regex REGEX_EPISODE = Regex.of("^(?:.*?(?: - |: ))?(\\d+)\\. díl(?:(?: - |: )(.*))?$");
	
	// Allow to create an instance when registering the engine
	NovaVoyoEngine() {
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
		return ListTask.of((task) -> {
			// Ensure the user is logged in
			VoyoAccount.login();
			
			// Obtain the main iframe for the video
			Document document = HTML.from(uri);
			Element elIframe = document.selectFirst(".js-detail-player .iframe-wrap iframe");
			if(elIframe == null)
				return;
			
			// Obtain the embedded iframe document to extract the player settings
			String embedURL = elIframe.absUrl("src");
			Document embedDoc = HTML.from(Net.uri(embedURL));
			
			VoyoError error;
			if(!(error = checkForError(embedDoc)).isSuccess()) {
				switch(error.type()) {
					case "player_parental_profile_age_required":
						if(VoyoAccount.bypassAgeRestriction()) {
							embedDoc = HTML.from(Net.uri(embedURL)); // Retry
							
							if(checkForError(embedDoc).isSuccess()) {
								break;
							}
						}
						// FALL-THROUGH
					default:
						throw new IllegalStateException("Error " + error.event() + ": " + error.type());
				}
			}
			
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
			MediaSource source = MediaSource.of(this);
			
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
		if(!host.equals("voyo.nova.cz"))
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
		
		private static final URI URI_ENDPOINT = Net.uri("https://voyo.nova.cz/api/v1/");
		
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
		
		private static final Response.OfStream request(String action, Map<String, Object> args) throws Exception {
			URI uri = URI_ENDPOINT.resolve(action + '?' + Net.queryString(args));
			Request request = Request.of(uri)
				.addHeaders("Referer", "https://voyo.nova.cz/", "X-Requested-With", "XMLHttpRequest")
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
				String url = elLink.absUrl("href");
				String title = elLink.text().trim();
				Program program = new Program(Net.uri(url), title, "programId", programId);
				
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
				Response.OfStream response) throws Exception {
			String content = new String(response.stream().readAllBytes(), Shared.CHARSET);
			
			if(content.isEmpty()) {
				// Nothing to be shown, therefore no more items
				return RESULT_NO_MORE_ITEMS;
			}
			
			Document document = HTML.parse(content, response.uri());
			Elements items = document.select("article");
			
			// Since order is always ascending but we want descending by default,
			// use the ListIterator to reverse the order.
			for(ListIterator<Element> it = items.listIterator(items.size()); it.hasPrevious();) {
				Element elWrapper = it.previous();
				Element elLink = elWrapper.selectFirst(".title > a");
				String url = elLink.absUrl("href");
				String title = String.format("%d. série - %s", season.number(), elLink.text().trim());
				Episode episode = new Episode(program, Net.uri(url), title);
				
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
				return parseEpisodes(task, program, season, response);
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
		
		private static final Document getProgramDetail(Program program) throws Exception {
			String action = "page/detail-url";
			Map<String, Object> args = Map.of(
				"layout_parts[]", "40-10",
				"url", program.uri().toString()
			);
			
			try(Response.OfStream response = request(action, args)) {
				JSONCollection json = JSON.read(response.stream());
				URI uri = Net.uri(json.getString("data.redirect.url"));
				return HTML.from(Request.of(uri).GET());
			}
		}
		
		private static final List<Season> getSeasons(Program program) throws Exception {
			List<Season> seasons = new ArrayList<>();
			
			Document document = getProgramDetail(program);
			Elements elItems = document.select("#episodesDropdown + .dropdown-menu .dropdown-item");
			int numOfSeasons = elItems.size();
			
			for(int i = 0; i < numOfSeasons; ++i) {
				Element elItem = elItems.get(i);
				String id = elItem.attr("data-season-id");
				int number = numOfSeasons - i;
				seasons.add(new Season(id, number));
			}
			
			return seasons;
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
				for(Season season : getSeasons(program)) {
					if(!loopListEpisodes(task, program, season)) {
						return; // Do not continue
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
		
		private static final URI URI_MY_ACCOUNT;
		private static final URI URI_AGE_RESTRICTION;
		private static final AtomicBoolean isLoginInProcess = new AtomicBoolean();
		private static final AtomicBoolean isLoggedIn = new AtomicBoolean();
		
		static {
			URI_MY_ACCOUNT = Net.uri("https://voyo.nova.cz/muj-profil");
			URI_AGE_RESTRICTION = Net.uri("https://voyo.nova.cz/obrazovky-prehravace/rodicovska-kontrola-profil");
		}
		
		// Forbid anyone to create an instance of this class
		private VoyoAccount() {
		}
		
		private static final List<HttpCookie> savedCookies(URI uri) throws Exception {
			// Get the top-level domain so that all the cookies are included
			String domain = uri.getHost();
			String[] parts = domain.split("\\.", 3);
			if(parts.length < 2)
				throw new IllegalStateException("Invalid domain");
			int i = parts.length < 3 ? 0 : 1;
			domain = parts[i] + '.' + parts[i + 1];
			
			String tlDomain = domain;
			CookieStore cookieStore = Web.cookieManager().getCookieStore();
			return cookieStore.getCookies().stream()
				.filter((c) -> c.getDomain().endsWith(tlDomain))
				.collect(Collectors.toList());
		}
		
		private static final PluginConfiguration configuration() {
			return PLUGIN.getContext().getConfiguration();
		}
		
		private static final void addDeviceToRemoveOnNextStart(Device device) {
			SSDCollection value = devicesToRemoveOnNextStart();
			if(value == null) value = SSDCollection.emptyArray();
			value.add(device.removeURL());
			configuration().writer().set("devicesToRemove", value);
			saveConfiguration();
		}
		
		private static final void clearDevicesToRemoveOnNextStart() {
			PluginConfiguration configuration = configuration();
			Configuration.Writer writer = configuration.writer();
			writer.set("devicesToRemove", SSDCollection.emptyArray());
			saveConfiguration();
		}
		
		private static final Path configurationPath() {
			return NIO.localPath("resources/config/" + PLUGIN.getContext().getPlugin().instance().name() + ".ssdf");
		}
		
		private static final SSDCollection devicesToRemoveOnNextStart() {
			// Due to behavior of the current ArrayConfigurationProperty we must acquire the list
			// using indirect reading and parsing of the configuration file.
			return SSDF.read(configurationPath().toFile()).getCollection("devicesToRemove", null);
		}
		
		private static final void saveConfiguration() {
			Ignore.callVoid(() -> configuration().writer().save(configurationPath()), MediaDownloader::error);
		}
		
		private static final void deviceReset() throws Exception {
			// These two lines forces to "reset" the current device on the Voyo website.
			// I don't know how it works exactly, but I assume it has something to do with
			// some logic such as: No token is present, regenerate it all again.
			// Thus resetting all the tokens and values, virtually creating a new device.
			// The procedure to do this is as follows:
			// (1) Remove all cookies.
			// (2) Visit any (logged-in-only?) page.
			Web.cookieManager().getCookieStore().removeAll();
			Web.request(Request.of(URI_MY_ACCOUNT).GET());
		}
		
		public static final boolean isLoggedIn() {
			return isLoggedIn.get();
		}
		
		public static final boolean login() throws Exception {
			if(isLoggedIn()) return true; // Already logged in
			
			if(!Authenticator.areLoginDetailsPresent())
				return false;
			
			// Guard accessing this method
			if(!isLoginInProcess.compareAndSet(false, true))
				return false;
			
			// This is more complicated than it should be, but since we cannot
			// get the remove URL of the current device, we have to obtain it
			// some other way. That is:
			// (1) Login normally. A new device is created.
			// (2) Obtain the list of devices, including the current device.
			//     The current device however has removeURL=null. A little note
			//     is that the list cannot be reordered, so that simplifies
			//     some things. Also, remember all cookies related to this
			//     login session.
			// (3) Reset the device and log in. Another new device is created.
			// (4) Again, obtain the list of devices, the previous device
			//     (the one we want) finally has non-empty removeURL, so we
			//     remember it.
			// (5) Again, reset the device and reset the login session cookies.
			// (6) Now, we are logged in as the previous (the one we want) device,
			//     so obtain the removeURL of the previous device (the dummy one).
			// (7) Finally, remove the dummy device and append the removeURL of
			//     the current device to list of devices to remove later, since
			//     a device cannot remove itself.
			
			// Log in to the account normally
			if(!Authenticator.login())
				throw new IllegalStateException("Unable to log in");
			
			// Remove any previously created devices that should be removed
			SSDCollection devicesToRemove = devicesToRemoveOnNextStart();
			if(devicesToRemove != null) {
				// Remove all devices (using their removeURL) that are present
				for(SSDObject object : devicesToRemove.objectsIterable()) {
					DeviceManager.removeDevice(object.stringValue());
				}
				
				// Clear all the devices regardless of whether any of them has been removed or not
				clearDevicesToRemoveOnNextStart();
			}
			
			List<Device> devices;
			
			// Remember the login session cookies for later use
			URI cookiesURI = URI_MY_ACCOUNT;
			List<HttpCookie> cookies = savedCookies(cookiesURI);
			// Remember the list of removeURLs of devices for later use
			devices = DeviceManager.listDevices();
			Set<String> devicesRemoveURLs = devices.stream().map(Device::removeURL).filter(Objects::nonNull)
					.collect(Collectors.toCollection(LinkedHashSet::new)); // Use linked set to preserve order
			
			// Reset the device and log in again to create a new device
			deviceReset();
			Authenticator.login();
			
			// Find the previous device
			devices = DeviceManager.listDevices();
			Device previousDevice = devices.stream()
					.filter((d) -> d.removeURL() != null && !devicesRemoveURLs.contains(d.removeURL()))
					.findFirst().orElse(null);
			
			if(previousDevice == null)
				throw new IllegalStateException("Unable to obtain the primary device");
			
			// Add the previous device to the list of known removeURLs
			devicesRemoveURLs.add(previousDevice.removeURL());
			
			// Reset the device and switch to the first device
			deviceReset();
			CookieStore cookieStore = Web.cookieManager().getCookieStore();
			cookieStore.removeAll();
			cookies.forEach((v) -> cookieStore.add(cookiesURI, v));
			Authenticator.login();
			
			// Obtain the second (dummy) device
			devices = DeviceManager.listDevices();
			Device dummyDevice = devices.stream()
					.filter((d) -> d.removeURL() != null && !devicesRemoveURLs.contains(d.removeURL()))
					.findFirst().orElse(null);
			
			if(dummyDevice == null)
				throw new IllegalStateException("Unable to obtain the dummy device");
			
			// Remove the dummy device since we don't need it
			DeviceManager.removeDevice(dummyDevice);
			
			// The current device unfortunately cannot remove itself, it must be done on the next
			// application use using another (new) device.
			addDeviceToRemoveOnNextStart(previousDevice);
			
			isLoggedIn.set(true);
			isLoginInProcess.set(false);
			return true;
		}
		
		public static boolean bypassAgeRestriction() throws Exception {
			String body = Net.queryString(Map.of(
				"birth_day", 1,
				"birth_month", 1,
				"birth_year", 2000,
				"save", "Uložit",
				"_do", "content212-userAgeForm-form-submit"
			));
			
			try(Response.OfStream response = Web.requestStream(Request.of(URI_AGE_RESTRICTION).POST(body))) {
				return response.statusCode() == 200;
			}
		}
	}
	
	private static final class Authenticator {
		
		private static final String URL_LOGIN = "https://voyo.nova.cz/prihlaseni";
		private static final String URL_REDIRECT_LOGIN = "https://voyo.nova.cz/muj-profil";
		
		// Forbid anyone to create an instance of this class
		private Authenticator() {
		}
		
		private static final PluginConfiguration configuration() {
			return PLUGIN.getContext().getConfiguration();
		}
		
		private static final <T> T valueOrElse(String propertyName, T orElse) {
			return Optional.<ConfigurationProperty<T>>ofNullable(configuration().property(propertyName))
					       .map(ConfigurationProperty::value).orElse(orElse);
		}
		
		private static final String username() {
			return valueOrElse("authData_email", "");
		}
		
		private static final String password() {
			return valueOrElse("authData_password", "");
		}
		
		private static final boolean login(String username, String password) throws Exception {
			if(username.isEmpty() || password.isEmpty())
				return false;
			
			URI uri = Net.uri(URL_LOGIN);
			Document document = HTML.from(uri);
			String argDo = document.selectFirst("input[type='hidden'][name='_do']").val();
			
			HttpHeaders headers = Web.Headers.ofSingle(
	 			"Referer", URL_LOGIN
	 		);
			
			Map<String, Object> args = Map.of(
	            "email", username,
	            "password", password,
	            "login", "Přihlásit",
	            "_do", argDo
	        );
			
			String body = Net.queryString(args);
			Response.OfString response = Web.request(Request.of(uri).headers(headers).POST(body));
			return response.uri().toString().equals(URL_REDIRECT_LOGIN);
		}
		
		public static final boolean login() throws Exception {
			return login(username(), password());
		}
		
		public static final boolean areLoginDetailsPresent() {
			return !username().isEmpty() && !password().isEmpty();
		}
	}
	
	private static final class DeviceManager {
		
		private static final String URL_DEVICES = "https://voyo.nova.cz/sprava-zarizeni";
		
		private static final String SELECTOR_DEVICES = "main .list > .content";
		
		// Forbid anyone to create an instance of this class
		private DeviceManager() {
		}
		
		public static final List<Device> listDevices() throws Exception {
			List<Device> devices = new ArrayList<>();
			
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
			Document document = HTML.from(Net.uri(URL_DEVICES));
			for(Element elDevice : document.select(SELECTOR_DEVICES)) {
				boolean isCurrent = elDevice.hasClass("-current");
				boolean isActive = !elDevice.parent().parent().hasClass("-forbidden");
				
				String name = elDevice.selectFirst("> .type > .item").text();
				String description = elDevice.selectFirst("> .type > .description").text();
				
				LocalDate dateActivation = LocalDate.parse(
					elDevice.selectFirst("> div:nth-child(2) > .date").textNodes().stream()
						.map(TextNode::text).reduce("", (a, b) -> a + b).trim(),
					formatter
				);
				LocalDate dateLastUsed = LocalDate.parse(
					elDevice.selectFirst("> div:nth-child(3) > .date").textNodes().stream()
						.map(TextNode::text).reduce("", (a, b) -> a + b).trim(),
					formatter
				);
				
				String removeURL = Optional.ofNullable(elDevice.selectFirst("> .action-field > a"))
						.map((a) -> a.absUrl("data-confirm-url")).orElse(null);
				
				devices.add(new Device(isCurrent, isActive, name, description, dateActivation,
					dateLastUsed, removeURL));
			}
			
			return devices;
		}
		
		public static final void removeDevice(Device device) throws Exception {
			removeDevice(device.removeURL());
		}
		
		private static final void removeDevice(String removeURL) throws Exception {
			if(removeURL == null) return; // Nothing to remove
			Web.request(Request.of(Net.uri(removeURL)).GET());
		}
	}
	
	private static final class Device {
		
		private final boolean isCurrent;
		private final boolean isActive;
		private final String name;
		private final String description;
		private final LocalDate dateActivation;
		private final LocalDate dateLastUsed;
		private final String removeURL;
		
		public Device(boolean isCurrent, boolean isActive, String name, String description, LocalDate dateActivation,
				LocalDate dateLastUsed, String removeURL) {
			this.isCurrent = isCurrent;
			this.isActive = isActive;
			this.name = Objects.requireNonNull(name);
			this.description = Objects.requireNonNull(description);
			this.dateActivation = Objects.requireNonNull(dateActivation);
			this.dateLastUsed = Objects.requireNonNull(dateLastUsed);
			this.removeURL = removeURL; // Can be null
		}
		
		@SuppressWarnings("unused")
		public boolean isCurrent() {
			return isCurrent;
		}
		
		@SuppressWarnings("unused")
		public boolean isActive() {
			return isActive;
		}
		
		@SuppressWarnings("unused")
		public String name() {
			return name;
		}
		
		@SuppressWarnings("unused")
		public String description() {
			return description;
		}
		
		@SuppressWarnings("unused")
		public LocalDate dateActivation() {
			return dateActivation;
		}
		
		@SuppressWarnings("unused")
		public LocalDate dateLastUsed() {
			return dateLastUsed;
		}
		
		public String removeURL() {
			return removeURL;
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