package sune.app.mediadown.server.sledovanitv;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient.Redirect;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.authentication.CredentialsManager;
import sune.app.mediadown.authentication.EmailCredentials;
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
import sune.app.mediadown.media.format.M3U.M3USegment;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
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
				+ "?recordId=%{recordId}d"
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
	
	private static final void displayError(String name) {
		Translation tr = translation().getTranslation("error");
		String message = tr.getSingle("value." + name);
		tr = tr.getTranslation("media_error");
		Dialog.showContentInfo(tr.getSingle("title"), tr.getSingle("text"), message);
	}
	
	private static final JSONCollection recordingInfo(URI uri) throws Exception {
		int recordId = Integer.valueOf(Utils.afterFirst(uri.getFragment(), ":"));
		URI uriInfo = Net.uri(Utils.format(URI_TEMPLATE_RECORDING, "recordId", recordId));
		
		try(Response.OfStream response = Web.requestStream(Request.of(uriInfo).GET())) {
			return JSON.read(response.stream());
		}
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			// Must be logged in to download the recording
			if(!Authenticator.login()) {
				return; // Failed to log in, do not continue
			}
			
			JSONCollection info = recordingInfo(uri);
			URI streamUri = Net.uri(info.getString("url"));
			String title = info.getString("title");
			MediaSource source = MediaSource.of(this);
			
			List<Media.Builder<?, ?>> builders = MediaUtils.createMediaBuilders(
				source, streamUri, uri, title, MediaLanguage.UNKNOWN, MediaMetadata.empty()
			);
			
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
					
					MediaMetadata metadata = MediaMetadata.of(
						"subtitles.retime.strategy", "startAtZero",
						"subtitles.retime.startTime", startTime,
						"subtitles.retime.ignoreHours", true
					);
					
					mb.metadata(MediaMetadata.of(mb.metadata(), metadata));
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
		
		private Authenticator() {
		}
		
		private static final boolean doLogin(String username, String password) throws Exception {
			if(username == null || username.isBlank() || password == null || password.isBlank()) {
				displayError("not_logged_in");
				return false; // Do not continue
			}
			
			String body = Net.queryString(
				"username", username,
				"password", password,
				"login", "Přihlásit",
				"_do", "userLoginControl-signInForm-submit"
			);
			
			// This cookie is important to be able to login
			HttpCookie cookie = new HttpCookie("_nss", "1");
			cookie.setPath("/");
			cookie.setSecure(true);
			cookie.setDomain("sledovanitv.cz");
			cookie.setHttpOnly(true);
			Web.Cookies.add(URI_LOGIN, cookie);
			
			try(Response.OfStream response = Web.requestStream(
				Request.of(URI_LOGIN)
					.addHeader("Referer", "https://sledovanitv.cz/welcome/login")
					.followRedirects(Redirect.NEVER)
					.POST(body, "application/x-www-form-urlencoded")
			)) { /* Do nothing */ }
			
			// Select the first available profile
			Document document = HTML.from(Request.of(Net.uri("https://sledovanitv.cz/profile")).GET());
			Element profile = document.selectFirst(".profiles__list--item > a");
			Web.requestStream(Request.of(Net.uri(profile.absUrl("href"))).GET());
			
			return true;
		}
		
		public static final boolean login() throws Exception {
			return doLogin(AuthenticationData.email(), AuthenticationData.password());
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