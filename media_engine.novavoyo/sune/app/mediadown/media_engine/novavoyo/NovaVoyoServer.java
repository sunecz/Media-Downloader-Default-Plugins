package sune.app.mediadown.media_engine.novavoyo;

import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import javafx.scene.image.Image;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.Shared;
import sune.app.mediadown.configuration.Configuration;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.server.Server;
import sune.app.mediadown.util.CheckedBiFunction;
import sune.app.mediadown.util.CheckedSupplier;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Reflection2;
import sune.app.mediadown.util.Reflection3;
import sune.app.mediadown.util.Threads;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.WorkerProxy;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDF;
import sune.util.ssdf2.SSDObject;

public final class NovaVoyoServer implements Server {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	private static final Pattern REGEX_EPISODE = Pattern.compile("^(?:.*?(?: - |: ))?(\\d+)\\. díl(?:(?: - |: )(.*))?$");
	
	// Allow to create an instance when registering the engine
	NovaVoyoServer() {
	}
	
	private static final VoyoError checkForError(Document document) {
		if(!document.body().hasClass("error"))
			return VoyoError.ofSuccess();
		
		for(Element elScript : document.select("script:not([src])")) {
			String content = elScript.html();
			int index;
			if((index = content.indexOf("klebetnica(")) >= 0) {
				content = Utils.bracketSubstring(content, '{', '}', false, index, content.length());
				SSDCollection data = SSDF.read(content);
				return VoyoError.ofFailure(data);
			}
		}
		
		return VoyoError.ofFailure(null);
	}
	
	private static final String mediaTitle(SSDCollection streamInfo) {
		// NovaPlus has weird naming, this is actually correct
		String programName = streamInfo.getDirectString("episode", "");
		String episodeText = streamInfo.getDirectString("programName", "");
		int numSeason = streamInfo.getDirectInt("seasonNumber", 0);
		int numEpisode = -1;
		String episodeName = null;
		Matcher matcher = REGEX_EPISODE.matcher(episodeText);
		if(matcher.matches()) {
			numEpisode = Integer.parseInt(matcher.group(1));
			episodeName = Optional.ofNullable(matcher.group(2)).orElse("");
		}
		return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
	}
	
	// ----- Internal methods
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	private final List<Media> internal_getMedia(String url) throws Exception {
		return internal_getMedia(url, _dwp, (p, a) -> true);
	}
	
	private final List<Media> internal_getMedia(String url, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
		List<Media> sources = new ArrayList<>();
		
		// Ensure the user is logged in
		VoyoAccount.login();
		
		// Obtain the main iframe for the video
		Document document = FastWeb.document(Utils.uri(url));
		Element elIframe = document.selectFirst(".iframe-wrap iframe");
		if(elIframe == null)
			return null;
		
		// Obtain the embedded iframe document to extract the player settings
		String embedURL = elIframe.absUrl("src");
		document = FastWeb.document(Utils.uri(embedURL));
		
		VoyoError error;
		if(!(error = checkForError(document)).isSuccess())
			throw new IllegalStateException("Error " + error.event() + ": " + error.type());
		
		SSDCollection settings = null;
		for(Element elScript : document.select("script:not([src])")) {
			String content = elScript.html();
			int index;
			if((index = content.indexOf("Player.init")) >= 0) {
				content = Utils.bracketSubstring(content, '{', '}', false, index, content.length());
				settings = JSON.read(content);
				break;
			}
		}
		
		if(settings == null)
			return null; // Do not continue
		
		SSDCollection tracks = settings.getDirectCollection("tracks");
		URI sourceURI = Utils.uri(url);
		MediaSource source = MediaSource.of(this);
		String title = mediaTitle(settings.getCollection("plugins.measuring.streamInfo"));
		for(SSDCollection node : tracks.collectionsIterable()) {
			MediaFormat format = MediaFormat.fromName(node.getName());
			for(SSDCollection coll : ((SSDCollection) node).collectionsIterable()) {
				String videoURL = coll.getDirectString("src");
				if(format == MediaFormat.UNKNOWN)
					format = MediaFormat.fromPath(videoURL);
				MediaLanguage language = MediaLanguage.ofCode(coll.getDirectString("lang"));
				List<Media> media = MediaUtils.createMedia(source, Utils.uri(videoURL), sourceURI,
					title, language, MediaMetadata.empty());
				for(Media s : media) {
					sources.add(s);
					if(!function.apply(proxy, s))
						return null; // Do not continue
				}
			}
		}
		
		return sources;
	}
	
	// -----
	
	@Override
	public List<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return internal_getMedia(uri.toString());
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
	
	private static final class VoyoAccount {
		
		private static final String URL_MY_ACCOUNT = "https://voyo.nova.cz/muj-profil";
		private static final AtomicBoolean isLoginInProcess = new AtomicBoolean();
		private static final AtomicBoolean isLoggedIn = new AtomicBoolean();
		
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
			CookieStore cookieStore = FastWeb.cookieManager().getCookieStore();
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
			return SSDF.read(configurationPath().toFile()).getDirectCollection("devicesToRemove", null);
		}
		
		private static final void saveConfiguration() {
			Utils.ignore(() -> configuration().writer().save(configurationPath()), MediaDownloader::error);
		}
		
		private static final void deviceReset() throws Exception {
			// These two lines forces to "reset" the current device on the Voyo website.
			// I don't know how it works exactly, but I assume it has something to do with
			// some logic such as: No token is present, regenerate it all again.
			// Thus resetting all the tokens and values, virtually creating a new device.
			// The procedure to do this is as follows:
			// (1) Remove all cookies.
			// (2) Visit any (logged-in-only?) page.
			FastWeb.cookieManager().getCookieStore().removeAll();
			FastWeb.getRequest(Utils.uri(URL_MY_ACCOUNT), Map.of());
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
			URI cookiesURI = Utils.uri(URL_MY_ACCOUNT);
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
			CookieStore cookieStore = FastWeb.cookieManager().getCookieStore();
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
			
			URI uri = Utils.uri(URL_LOGIN);
			Document document = FastWeb.document(uri);
			String argDo = document.selectFirst("input[type='hidden'][name='_do']").val();
			
			Map<String, String> headers = Map.of(
	 			"Content-Type", "application/x-www-form-urlencoded",
	 			"Referer", URL_LOGIN
	 		);
			
			Map<String, Object> args = Map.of(
	            "email", username,
	            "password", password,
	            "login", "Přihlásit",
	            "_do", argDo
	        );
			
			HttpResponse<String> response = FastWeb.postRequest(uri, headers, args);
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
			Document document = FastWeb.document(Utils.uri(URL_DEVICES));
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
			FastWeb.getRequest(Utils.uri(removeURL), Map.of());
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
		
		private VoyoError(boolean isSuccess, SSDCollection data) {
			this.isSuccess = isSuccess;
			
			String event = null;
			String type = null;
			
			if(data != null) {
				event = data.getDirectString("event", "");
				type = data.getString("data.type", "");
			}
			
			this.event = event;
			this.type = type;
		}
		
		public static final VoyoError ofSuccess() {
			return new VoyoError(true, null);
		}
		
		public static final VoyoError ofFailure(SSDCollection data) {
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
	
	private static final class FastWeb {
		
		private static final ConcurrentVarLazyLoader<CookieManager> cookieManager
			= ConcurrentVarLazyLoader.of(FastWeb::ensureCookieManager);
		private static final ConcurrentVarLazyLoader<HttpClient> httpClient
			= ConcurrentVarLazyLoader.of(FastWeb::buildHttpClient);
		private static final ConcurrentVarLazyLoader<HttpRequest.Builder> httpRequestBuilder
			= ConcurrentVarLazyLoader.of(FastWeb::buildHttpRequestBuilder);
		
		// Forbid anyone to create an instance of this class
		private FastWeb() {
		}
		
		private static final CookieManager ensureCookieManager() throws Exception {
			Reflection3.invokeStatic(Web.class, "ensureCookieManager");
			return (CookieManager) Reflection2.getField(Web.class, null, "COOKIE_MANAGER");
		}
		
		private static final HttpClient buildHttpClient() throws Exception {
			return HttpClient.newBuilder()
						.connectTimeout(Duration.ofMillis(5000))
						.executor(Threads.Pools.newWorkStealing())
						.followRedirects(Redirect.NORMAL)
						.cookieHandler(cookieManager.value())
						.version(Version.HTTP_2)
						.build();
		}
		
		private static final HttpRequest.Builder buildHttpRequestBuilder() throws Exception {
			return HttpRequest.newBuilder()
						.setHeader("User-Agent", Shared.USER_AGENT);
		}
		
		private static final HttpRequest.Builder maybeAddHeaders(HttpRequest.Builder request, Map<String, String> headers) {
			if(!headers.isEmpty()) {
				request.headers(
					headers.entrySet().stream()
						.flatMap((e) -> Stream.of(e.getKey(), e.getValue()))
						.toArray(String[]::new)
				);
			}
			return request;
		}
		
		public static final String bodyString(Map<String, Object> data) {
			StringBuilder sb = new StringBuilder();
			
			boolean first = true;
			for(Entry<String, Object> e : data.entrySet()) {
				if(first) first = false; else sb.append('&');
				sb.append(URLEncoder.encode(e.getKey(), Shared.CHARSET))
				  .append('=')
				  .append(URLEncoder.encode(Objects.toString(e.getValue()), Shared.CHARSET));
			}
			
			return sb.toString();
		}
		
		public static final HttpResponse<String> getRequest(URI uri, Map<String, String> headers) throws Exception {
			HttpRequest request = maybeAddHeaders(httpRequestBuilder.value().copy().GET().uri(uri), headers).build();
			HttpResponse<String> response = httpClient.value().sendAsync(request, BodyHandlers.ofString(Shared.CHARSET)).join();
			return response;
		}
		
		@SuppressWarnings("unused")
		public static final String get(URI uri, Map<String, String> headers) throws Exception {
			return getRequest(uri, headers).body();
		}
		
		public static final Document document(URI uri, Map<String, String> headers) throws Exception {
			HttpResponse<String> response = getRequest(uri, headers);
			return Utils.parseDocument(response.body(), response.uri());
		}
		
		public static final Document document(URI uri) throws Exception {
			return document(uri, Map.of());
		}
		
		public static final HttpResponse<String> postRequest(URI uri, Map<String, String> headers,
				Map<String, Object> data) throws Exception {
			BodyPublisher body = BodyPublishers.ofString(bodyString(data), Shared.CHARSET);
			HttpRequest request = maybeAddHeaders(httpRequestBuilder.value().copy().POST(body).uri(uri), headers).build();
			HttpResponse<String> response = httpClient.value().sendAsync(request, BodyHandlers.ofString(Shared.CHARSET)).join();
			return response;
		}
		
		public static final CookieManager cookieManager() throws Exception {
			return cookieManager.value();
		}
		
		private static final class ConcurrentVarLazyLoader<T> {
			
			private final AtomicBoolean isSet = new AtomicBoolean();
			private final AtomicBoolean isSetting = new AtomicBoolean();
			private volatile T value;
			
			private final CheckedSupplier<T> supplier;
			
			private ConcurrentVarLazyLoader(CheckedSupplier<T> supplier) {
				this.supplier = Objects.requireNonNull(supplier);
			}
			
			public static final <T> ConcurrentVarLazyLoader<T> of(CheckedSupplier<T> supplier) {
				return new ConcurrentVarLazyLoader<>(supplier);
			}
			
			public final T value() throws Exception {
				if(isSet.get()) return value; // Already set
				
				while(!isSet.get()
							&& !isSetting.compareAndSet(false, true)) {
					synchronized(isSetting) {
						try {
							isSetting.wait();
						} catch(InterruptedException ex) {
							// Ignore
						}
					}
					if(isSet.get()) return value; // Already set
				}
				
				try {
					value = supplier.get();
					isSet.set(true);
					return value;
				} finally {
					isSetting.set(false);
					synchronized(isSetting) {
						isSetting.notifyAll();
					}
				}
			}
		}
	}
}