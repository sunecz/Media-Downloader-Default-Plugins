package sune.app.mediadown.server.sledovanitv;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient.Redirect;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.authentication.CredentialsManager;
import sune.app.mediadown.authentication.EmailCredentials;
import sune.app.mediadown.concurrent.VarLoader;
import sune.app.mediadown.configuration.Configuration;
import sune.app.mediadown.entity.Server;
import sune.app.mediadown.gui.Dialog;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaContainer;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SegmentedMedia;
import sune.app.mediadown.media.fix.MediaFixer;
import sune.app.mediadown.media.format.M3U.M3USegment;
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
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;

public class SledovaniTVServer implements Server {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	private static final String URI_TEMPLATE_RECORDING;
	
	static {
		URI_TEMPLATE_RECORDING = "https://sledovanitv.cz/playback/pvr-info"
				+ "?recordId=%{recordId}s"
				+ "&format=m3u8/m3u8"
				+ "&drm=widevine";
	}
	
	// Allow to create an instance when registering the server
	SledovaniTVServer() {
	}
	
	private static final Translation translation() {
		String path = "plugin." + PLUGIN.getContext().getPlugin().instance().name();
		return MediaDownloader.translation().getTranslation(path);
	}
	
	private static final PluginConfiguration configuration() {
		return PLUGIN.getContext().getConfiguration();
	}
	
	private static final void displayError(String name) {
		Translation tr = translation().getTranslation("error");
		String message = tr.getSingle("value." + name);
		tr = tr.getTranslation("media_error");
		Dialog.showContentInfo(tr.getSingle("title"), tr.getSingle("text"), message);
	}
	
	private static final JSONCollection recordingInfo(Authenticator.Session session, URI uri) throws Exception {
		String uriFragment = uri.getFragment();
		
		if(uriFragment == null) {
			throw new IllegalArgumentException("Unsupported URI");
		}
		
		String recordId = Utils.afterFirst(uriFragment, ":");
		URI uriInfo = Net.uri(Utils.format(URI_TEMPLATE_RECORDING, "recordId", recordId));
		
		try(Response.OfStream response = Web.requestStream(
			Request.of(uriInfo)
				.addHeaders(session.headers())
				.followRedirects(Redirect.NEVER)
				.GET()
		)) {
			if(response.statusCode() != 200) {
				// Not logged in correctly
				return null;
			}
			
			return JSON.read(response.stream());
		}
	}
	
	private static final boolean isError(URI streamUri) throws Exception {
		try(Response response = Web.peek(Request.of(streamUri).followRedirects(Redirect.NEVER).HEAD())) {
			return Net.uriBasename(response.headers().firstValue("location").get()).toString().startsWith("error");
		}
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			Authenticator.Session session;
			
			// Must be logged in to download the recording
			if((session = Authenticator.login()) == null) {
				// Failed to log in, do not continue
				return;
			}
			
			JSONCollection info;
			int retry = 0;
			final int numOfRetries = 1;
			
			do {
				if(retry > 0) {
					// The info is still null, try to login again
					if((session = Authenticator.refresh()) == null) {
						// Failed to log in, do not continue
						return;
					}
				}
				
				info = recordingInfo(session, uri);
			} while(info == null && ++retry <= numOfRetries);
			
			if(info == null) {
				// Already retried enough times, nothing else to do
				throw new IllegalStateException("Unable to obtain media information");
			}
			
			URI streamUri = Net.uri(info.getString("url"));
			
			if(isError(streamUri)) {
				throw new IllegalStateException("Too many requests, wait a little bit and try again");
			}
			
			String title = info.getString("title");
			MediaSource source = MediaSource.of(this);
			MediaMetadata metadata = MediaMetadata.of(
				"profileId", session.profileId(),
				"deviceId", session.device().id()
			);
			
			List<Media.Builder<?, ?>> builders = MediaUtils.createMediaBuilders(
				source, streamUri, uri, title, MediaLanguage.UNKNOWN, metadata
			);
			
			// Since timestamps in the decrypted files are not always correct,
			// we must request both the video and audio to be fixed, if present.
			for(Media.Builder<?, ?> builder : builders) {
				if(!builder.format().isAnyOf(MediaFormat.M3U8, MediaFormat.DASH)) {
					continue; // Currently only M3U8 or DASH segments are supported
				}
				
				List<String> steps = new ArrayList<>();
				
				for(Media.Builder<?, ?> mb : ((MediaContainer.Builder<?, ?>) builder).media()) {
					// Only protected media have video and audio separated
					if(!mb.metadata().isProtected()) {
						continue;
					}
					
					steps.add(
						mb.type().is(MediaType.AUDIO)
							? MediaFixer.Steps.STEP_AUDIO_FIX_TIMESTAMPS
							: MediaFixer.Steps.STEP_VIDEO_FIX_TIMESTAMPS
					);
				}
				
				if(!steps.isEmpty()) {
					MediaMetadata fixMetadata = MediaMetadata.of(
						"media.fix.required", true,
						"media.fix.steps", steps
					);
					
					builder.metadata(MediaMetadata.of(builder.metadata(), fixMetadata));
				}
			}
			
			for(Media.Builder<?, ?> builder : builders) {
				if(!builder.format().is(MediaFormat.M3U8)) {
					continue; // Currently only M3U8 segments are supported
				}
				
				for(Media.Builder<?, ?> mb : ((MediaContainer.Builder<?, ?>) builder).media()) {
					if(!(mb instanceof SegmentedMedia.Builder)
							|| !mb.type().is(MediaType.SUBTITLES)
							|| !mb.format().is(MediaFormat.VTT)) {
						continue; // Currently only segmened VTT subtitles are supported
					}
					
					SegmentedMedia.Builder<?, ?> smb = (SegmentedMedia.Builder<?, ?>) mb;
					M3USegment firstSegment = (M3USegment) smb.segments().segments().get(0);
					String startTime = SegmentsHelper.timestamp(firstSegment);
					
					MediaMetadata subtitlesMetadata = MediaMetadata.of(
						"subtitles.retime.strategy", "startAtZero",
						"subtitles.retime.startTime", startTime,
						"subtitles.retime.ignoreHours", true
					);
					
					mb.metadata(MediaMetadata.of(mb.metadata(), subtitlesMetadata));
				}
			}
			
			for(Media.Builder<?, ?> builder : builders) {
				Media m = builder.build();
				
				if(!task.add(m)) {
					return; // Do not continue
				}
			}
		});
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
		if(host.startsWith("www.")) // www prefix
			host = host.substring(4);
		if(!host.equals("sledovanitv.cz")
				&& !host.equals("sledovanietv.sk"))
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
	
	private static final class Authenticator {
		
		private static final URI URI_LOGIN = Net.uri("https://sledovanitv.cz/welcome/login");
		
		private static final VarLoader<Session> session = VarLoader.ofChecked(Authenticator::createSession);
		
		private Authenticator() {
		}
		
		private static final String cookieOfName(List<HttpCookie> cookies, String name) {
			return cookies.stream()
						.filter((c) -> c.getName().equals(name))
						.map(HttpCookie::getValue)
						.findFirst().orElse(null);
		}
		
		private static final Path configurationPath() {
			return NIO.localPath("resources/config/" + PLUGIN.getContext().getPlugin().instance().name() + ".ssdf");
		}
		
		private static final Session loadSession() {
			PluginConfiguration configuration = configuration();
			String profileId = configuration.stringValue("profile_id");
			String deviceId = configuration.stringValue("device_id");
			String deviceAuth = configuration.stringValue("device_auth");
			String deviceTime = configuration.stringValue("device_time");
			
			if(profileId == null || profileId.isEmpty()
					|| deviceId == null || deviceId.isEmpty()
					|| deviceAuth == null || deviceAuth.isEmpty()
					|| deviceTime == null || deviceTime.isEmpty()) {
				return null; // Normalize to null
			}
			
			return new Session(
				profileId,
				new Device(deviceId, deviceAuth, deviceTime)
			);
		}
		
		private static final Device loadDeviceFromCookies() {
			URI uri = Net.uri("https://sledovanitv.cz");
			List<HttpCookie> cookies = Web.cookieManager().getCookieStore().get(uri);
			String deviceId = cookieOfName(cookies, "device_id");
			String deviceAuth = cookieOfName(cookies, "device_auth");
			String deviceTime = cookieOfName(cookies, "device_auth_set");
			
			if(deviceId == null || deviceId.isEmpty()
					|| deviceAuth == null || deviceAuth.isEmpty()
					|| deviceTime == null || deviceTime.isEmpty()) {
				return null; // Normalize to null
			}
			
			return new Device(deviceId, deviceAuth, deviceTime);
		}
		
		private static final void saveSession(Session session) throws IOException {
			Configuration.Writer writer = configuration().writer();
			String profileId = session.profileId();
			Device device = session.device();
			writer.set("profile_id", profileId);
			writer.set("device_id", device.id());
			writer.set("device_auth", device.auth());
			writer.set("device_time", device.time());
			writer.save(configurationPath());
		}
		
		private static final void removeSession() throws IOException {
			Configuration.Writer writer = configuration().writer();
			// Must use empty values, not null
			writer.set("profile_id", "");
			writer.set("device_id", "");
			writer.set("device_auth", "");
			writer.set("device_time", 0L);
			writer.save(configurationPath());
		}
		
		private static final Session doLogin(String username, String password) throws Exception {
			if(username == null || username.isBlank() || password == null || password.isBlank()) {
				displayError("not_logged_in");
				return null; // Do not continue
			}
			
			Session session;
			
			// Return the session from configuration, if it already exists
			if((session = loadSession()) != null) {
				return session;
			}
			
			String body = Net.queryString(
				"username", username,
				"password", password,
				"login", "Přihlásit",
				"_do", "userLoginControl-signInForm-submit"
			);
			
			try(Response.OfStream response = Web.requestStream(
				Request.of(URI_LOGIN)
					.addHeader("Referer", "https://sledovanitv.cz/welcome/login")
					.addCookie(Web.Cookie.builder("_nss", "1").path("/").secure(true).build())
					.followRedirects(Redirect.NEVER)
					.POST(body, "application/x-www-form-urlencoded")
			)) {
				String redirectUri = response.headers().firstValue("location").get();
				
				if(!redirectUri.equals("https://sledovanitv.cz/home")) {
					throw new IllegalStateException("Unable to log in");
				}
			}
			
			// Select the first available profile
			Document document = HTML.from(Request.of(Net.uri("https://sledovanitv.cz/profile")).GET());
			Element profile = document.selectFirst(".profiles__list--item > a");
			Web.requestStream(Request.of(Net.uri(profile.absUrl("href"))).GET());
			
			String profileId = Net.queryDestruct(profile.absUrl("href")).valueOf("profileId");
			Device device = loadDeviceFromCookies();
			
			if(profileId == null || profileId.isEmpty() || device == null) {
				throw new IllegalStateException("Invalid session");
			}
			
			session = new Session(profileId, device);
			// Save the session to the configuration for later requests
			saveSession(session);
			
			return session;
		}
		
		private static final Session createSession() throws Exception {
			return doLogin(AuthenticationData.email(), AuthenticationData.password());
		}
		
		public static final Session login() throws Exception {
			return session.valueChecked();
		}
		
		public static final void reset() throws Exception {
			removeSession(); // Discard the saved session
			Web.cookieManager().getCookieStore().removeAll(); // Clear session
			session.unset();
		}
		
		public static final Session refresh() throws Exception {
			reset();
			return login();
		}
		
		private static final class Device {
			
			private final String id;
			private final String auth;
			private final String time;
			
			public Device(String id, String auth, String time) {
				this.id = id;
				this.auth = auth;
				this.time = time;
			}
			
			public String id() { return id; }
			public String auth() { return auth; }
			public String time() { return time; }
		}
		
		private static final class Session {
			
			private final String profileId;
			private final Device device;
			private Map<String, List<String>> headers;
			
			public Session(String profileId, Device device) {
				this.profileId = Objects.requireNonNull(profileId);
				this.device = Objects.requireNonNull(device);
			}
			
			public String profileId() { return profileId; }
			public Device device() { return device; }
			
			public Map<String, List<String>> headers() {
				if(headers == null) {
					headers = Map.of(
						"Cookie", List.of(
							"device_id=" + device.id(),
							"device_auth=" + device.auth(),
							"device_auth_set=" + device.time()
						)
					);
				}
				
				return headers;
			}
		}
		
		private static final class AuthenticationData {
			
			private AuthenticationData() {
			}
			
			private static final String credentialsName() {
				return "plugin/" + PLUGIN.getContext().getPlugin().instance().name().replace('.', '/');
			}
			
			private static final EmailCredentials credentials() throws IOException {
				return (EmailCredentials) CredentialsManager.instance().get(credentialsName());
			}
			
			private static final String valueOrElse(Function<EmailCredentials, String> getter, String orElse) {
				return Ignore.defaultValue(
					() -> Opt.of(getter.apply(credentials()))
								.ifFalse(String::isBlank)
								.orElse(orElse),
					orElse
				);
			}
			
			public static final String email() {
				return valueOrElse(EmailCredentials::email, "");
			}
			
			public static final String password() {
				return valueOrElse(EmailCredentials::password, "");
			}
		}
	}
	
	private static final class SegmentsHelper {
		
		private static DateTimeFormatter dateTimeParser;
		
		private SegmentsHelper() {
		}
		
		private static final DateTimeFormatter dateTimeParser() {
			if(dateTimeParser == null) {
				dateTimeParser = new DateTimeFormatterBuilder()
					.append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
					.optionalStart().appendOffset("+HHMM", "+0000").optionalEnd()
					.toFormatter();
			}
			
			return dateTimeParser;
		}
		
		public static final String timestamp(M3USegment segment) {
			ZonedDateTime instant = Instant.from(dateTimeParser().parse(segment.dateTime())).atZone(ZoneOffset.UTC);
			return DateTimeFormatter.ISO_LOCAL_TIME.format(instant);
		}
	}
}