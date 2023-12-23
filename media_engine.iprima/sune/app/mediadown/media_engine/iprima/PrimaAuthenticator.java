package sune.app.mediadown.media_engine.iprima;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient.Redirect;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import sune.app.mediadown.authentication.CredentialsManager;
import sune.app.mediadown.concurrent.VarLoader;
import sune.app.mediadown.configuration.Configuration;
import sune.app.mediadown.media_engine.iprima.IPrimaEnginePlugin.IPrimaCredentials;
import sune.app.mediadown.media_engine.iprima.PrimaAuthenticator.Devices.Device;
import sune.app.mediadown.media_engine.iprima.PrimaAuthenticator.Profiles.Profile;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.MessageException;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.RPC;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.TranslatableException;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;
import sune.util.ssdf2.SSDCollection;

public final class PrimaAuthenticator {
	
	private static final String URL_OAUTH_LOGIN;
	private static final String URL_OAUTH_TOKEN;
	private static final String URL_OAUTH_AUTHORIZE;
	private static final String URL_USER_AUTH;
	private static final String URL_PROFILE_SELECT;
	private static final String URL_SUCCESSFUL_LOGIN;
	
	private static final VarLoader<SessionTokens> sessionTokens;
	private static final VarLoader<SessionData> sessionData;
	
	static {
		URL_OAUTH_LOGIN = "https://auth.iprima.cz/oauth2/login";
		URL_OAUTH_TOKEN = "https://auth.iprima.cz/oauth2/token";
		URL_OAUTH_AUTHORIZE = "https://auth.iprima.cz/oauth2/authorize";
		URL_USER_AUTH = "https://www.iprima.cz/sso/login?auth_token_code=%{code}s";
		URL_PROFILE_SELECT = "https://auth.iprima.cz/user/profile-select-perform/%{profile_id}s?continueUrl=/user/login";
		URL_SUCCESSFUL_LOGIN = "https://auth.iprima.cz/sso/auth-check";
		sessionTokens = VarLoader.ofChecked(PrimaAuthenticator::initSessionTokens);
		sessionData = VarLoader.ofChecked(PrimaAuthenticator::initSessionData);
	}
	
	// Forbid anyone to create an instance of this class
	private PrimaAuthenticator() {
	}
	
	private static final String loginOAuth(String email, String password) throws Exception {
		Response.OfString response = Web.request(Request.of(Net.uri(URL_OAUTH_LOGIN)).GET());
		String csrfToken = HTML.parse(response.body()).selectFirst("input[name='_csrf_token']").val();
		String body = Net.queryString("_email", email, "_password", password, "_csrf_token", csrfToken);
		response = Web.request(Request.of(Net.uri(URL_OAUTH_LOGIN)).POST(body));
		
		if(!isSuccessfulLoginURI(response.uri())) {
			throw new IncorrectAuthDataException();
		}
		
		return selectConfiguredProfile(response);
	}
	
	private static final boolean isSuccessfulLoginURI(URI uri) {
		return Utils.beforeFirst(uri.toString(), "?").equals(URL_SUCCESSFUL_LOGIN);
	}
	
	private static final String selectProfile(String profileId) throws Exception {
		URI uri = Net.uri(Utils.format(URL_PROFILE_SELECT, "profile_id", profileId));
		
		try(Response.OfStream response = Web.requestStream(Request.of(uri).GET())) {
			String responseUrl = response.uri().toString();
			return Net.queryDestruct(responseUrl).valueOf("code", null);
		}
	}
	
	private static final List<Profile> profiles(Response.OfString response) throws Exception {
		return Profiles.isProfileSelectPage(response.uri())
					? Profiles.extractProfiles(response.body())
					: Profiles.list();
	}
	
	private static final String selectConfiguredProfile(Response.OfString response) throws Exception {
		List<Profile> profiles = profiles(response);
		
		// At least one profile should be automatically available
		if(profiles.isEmpty()) {
			throw new IllegalStateException("No profile exists");
		}
		
		Profile profile = profiles.get(0);
		
		Configuration configuration = configuration();
		String selectedProfileId = configuration.stringValue("profile");
		
		if(selectedProfileId != null
				&& !selectedProfileId.isEmpty()
				&& !selectedProfileId.equals("auto")) {
			profile = profiles.stream()
				.filter((p) -> p.id().equals(selectedProfileId))
				.findFirst().orElse(profile);
		}
		
		return selectProfile(profile.id());
	}
	
	private static final SessionTokens sessionTokens(String code) throws Exception {
		if(code == null) {
			return null;
		}
		
		String body = Net.queryString(
			"scope", "openid+email+profile+phone+address+offline_access",
			"client_id", "prima_sso",
			"grant_type", "authorization_code",
			"code", code,
			"redirect_uri", "https://auth.iprima.cz/sso/auth-check"
		);
		
		try(Response.OfStream response = Web.requestStream(Request.of(Net.uri(URL_OAUTH_TOKEN)).POST(body))) {
			return SessionTokens.parse(JSON.read(response.stream()));
		}
	}
	
	private static final String authorize(SessionTokens tokens) throws Exception {
		String query = Net.queryString(
			"response_type", "token_code",
			"client_id", "sso_token",
			"token", tokens.tokenDataString()
		);
		
		URI uri = Net.uri(URL_OAUTH_AUTHORIZE + '?' + query);
		try(Response.OfStream response = Web.requestStream(Request.of(uri).GET())) {
			return JSON.read(response.stream()).getString("code", null);
		}
	}
	
	private static final boolean userAuth(String code) throws Exception {
		if(code == null) {
			return false;
		}
		
		URI uri = Net.uri(Utils.format(URL_USER_AUTH, "code", code));
		try(Response.OfStream response = Web.requestStream(Request.of(uri).followRedirects(Redirect.NEVER).GET())) {
			return response.statusCode() == 302;
		}
	}
	
	private static final Configuration configuration() {
		return IPrimaHelper.configuration();
	}
	
	private static final SessionTokens initSessionTokens() throws Exception {
		return sessionTokens(loginOAuth(
			AuthenticationData.email(),
			AuthenticationData.password()
		));
	}
	
	private static final SessionData initSessionData() throws Exception {
		SessionTokens tokens = sessionTokens();
		boolean success = userAuth(authorize(tokens));
		
		if(!success) {
			throw new IllegalStateException("Unsuccessful log in");
		}
		
		String profileId = Cached.profile().id();
		String deviceId = Cached.device().id();
		
		return new SessionData(tokens.rawString(), tokens.accessToken(), profileId, deviceId);
	}
	
	private static final SessionTokens sessionTokens() throws Exception {
		return sessionTokens.valueChecked();
	}
	
	public static final SessionData sessionData() throws Exception {
		return sessionData.valueChecked();
	}
	
	public static final List<Profile> profiles() throws Exception {
		return Cached.profiles();
	}
	
	public static final Profile profile() throws Exception {
		return Cached.profile();
	}
	
	public static final Device device() throws Exception {
		return Cached.device();
	}
	
	public static final class IncorrectAuthDataException extends TranslatableException {
		
		private static final long serialVersionUID = -2161157785248283759L;
		private static final String TRANSLATION_PATH = "error.incorrect_auth_data";
		
		public IncorrectAuthDataException() { super(TRANSLATION_PATH); }
		public IncorrectAuthDataException(Throwable cause) { super(TRANSLATION_PATH, cause); }
	}
	
	public static final class NoProfileFoundException extends TranslatableException {
		
		private static final long serialVersionUID = 4299510148961236319L;
		private static final String TRANSLATION_PATH = "error.no_profile_found";
		
		public NoProfileFoundException() { super(TRANSLATION_PATH); }
		public NoProfileFoundException(Throwable cause) { super(TRANSLATION_PATH, cause); }
	}
	
	private static final class Cached {
		
		private static final VarLoader<List<Profile>> profiles = VarLoader.ofChecked(Profiles::list);
		private static final VarLoader<Profile> profile = VarLoader.ofChecked(Cached::getProfile);
		private static final VarLoader<Device> device = VarLoader.ofChecked(Cached::getDevice);
		
		// Forbid anyone to create an instance of this class
		private Cached() {
		}
		
		private static final Profile getProfile() throws Exception {
			List<Profile> profiles = profiles();
			
			if(profiles.isEmpty()) {
				throw new NoProfileFoundException();
			}
			
			String profileId = AuthenticationData.profile();
			
			if(profileId.equalsIgnoreCase("auto")) {
				return profiles.get(0);
			}
			
			return profiles.stream()
				.filter((p) -> p.id().equalsIgnoreCase(profileId))
				.findFirst()
				.orElseGet(() -> profiles.get(0));
		}
		
		private static final Device getDevice() throws Exception {
			Device device = Devices.getDefault();
			
			if(device == null) {
				device = Devices.createDefault();
			}
			
			return device;
		}
		
		public static final List<Profile> profiles() throws Exception {
			return profiles.valueChecked();
		}
		
		public static final Profile profile() throws Exception {
			return profile.valueChecked();
		}
		
		public static final Device device() throws Exception {
			return device.valueChecked();
		}
	}
	
	public static final class Profiles {
		
		private static final String URL_PROFILE_PAGE = "https://auth.iprima.cz/user/profile-select";
		private static final String SELECTOR_PROFILE = ".profile-select";
		
		// Forbid anyone to create an instance of this class
		private Profiles() {
		}
		
		public static final boolean isProfileSelectPage(URI uri) {
			return uri.toString().startsWith(URL_PROFILE_PAGE);
		}
		
		public static final List<Profile> extractProfiles(String content) throws Exception {
			List<Profile> profiles = new ArrayList<>();
			Document document = HTML.parse(content);
			
			for(Element elButton : document.select(SELECTOR_PROFILE)) {
				String id = elButton.attr("data-identifier");
				String name = elButton.selectFirst(".card-body").text();
				profiles.add(new Profile(id, name));
			}
			
			return profiles;
		}
		
		public static final List<Profile> list() throws Exception {
			return extractProfiles(Web.request(Request.of(Net.uri(URL_PROFILE_PAGE)).GET()).body());
		}
		
		public static final class Profile {
			
			private final String id;
			private final String name;
			
			protected Profile(String id, String name) {
				this.id = Objects.requireNonNull(id);
				this.name = Objects.requireNonNull(name);
			}
			
			public String id() { return id; }
			public String name() { return name; }
		}
	}
	
	public static final class Devices {
		
		private static final String DEFAULT_DEVICE_NAME = "Media Downloader";
		private static final String DEFAULT_DEVICE_TYPE = "WEB";
		
		// Forbid anyone to create an instance of this class
		private Devices() {
		}
		
		private static final String accessToken() throws Exception {
			return sessionTokens().accessToken();
		}
		
		private static final Device parseDevice(JSONCollection data) {
			return new Device(
				data.getString("slotId"),
				data.getString("slotType"),
				data.getString("title")
			);
		}
		
		private static final Stream<Device> listStream() throws Exception {
			final String method = "user.device.slot.list";
			
			JSONCollection response = RPC.request(
				method,
				"_accessToken", accessToken()
			);
			
			return Utils.stream(response.getCollection("data").collectionsIterable())
						.map(Devices::parseDevice);
		}
		
		public static final List<Device> list() throws Exception {
			return listStream().collect(Collectors.toList());
		}
		
		public static final Device create(String name) throws Exception {
			final String method = "user.device.slot.add";
			
			JSONCollection response = RPC.request(
				method,
				"_accessToken", accessToken(),
				"deviceSlotName", name,
				"deviceSlotType", DEFAULT_DEVICE_TYPE,
				"deviceUid", null
			);
			
			// Handle possible errors, i.e. when the device limit has been exceeded.
			if(RPC.isError(response)) {
				throw new MessageException(response.getString("error.message"));
			}
			
			return parseDevice(response.getCollection("data"));
		}
		
		public static final Device createDefault() throws Exception {
			return create(DEFAULT_DEVICE_NAME);
		}
		
		public static final boolean isValid(Device device) throws Exception {
			final String method = "user.device.slot.check";
			
			JSONCollection response = RPC.request(
				method,
				"_accessToken", accessToken(),
				"slotId", device.id()
			);
			
			return response.getBoolean("data.valid");
		}
		
		public static final Device get(String name) throws Exception {
			return listStream().filter((d) -> d.name().equals(name)).findFirst().orElse(null);
		}
		
		public static final Device getDefault() throws Exception {
			return get(DEFAULT_DEVICE_NAME);
		}
		
		public static final class Device {
			
			private final String slotId;
			private final String slotType;
			private final String slotName;
			
			protected Device(String slotId, String slotType, String slotName) {
				this.slotId = Objects.requireNonNull(slotId);
				this.slotType = Objects.requireNonNull(slotType);
				this.slotName = Objects.requireNonNull(slotName);
			}
			
			public String id() { return slotId; }
			public String type() { return slotType; }
			public String name() { return slotName; }
		}
	}
	
	private static final class SessionTokens {
		
		private final String rawString;
		private final String accessToken;
		private final String refreshToken;
		
		protected SessionTokens(String rawString, String accessToken, String refreshToken) {
			this.rawString = rawString;
			this.accessToken = accessToken;
			this.refreshToken = refreshToken;
		}
		
		public static final SessionTokens parse(JSONCollection json) {
			return new SessionTokens(
				json.toString(true),
				json.getString("access_token"),
				json.getString("refresh_token")
			);
		}
		
		public final String tokenDataString() {
			SSDCollection data = SSDCollection.empty();
			data.setDirect("access_token", accessToken);
			data.setDirect("refresh_token", refreshToken);
			return Utils.base64URLEncode(data.toJSON(true));
		}
		
		public String rawString() { return rawString; }
		public String accessToken() { return accessToken; }
	}
	
	public static final class SessionData {
		
		private final String rawString;
		private final String accessToken;
		private final String profileId;
		private final String deviceId;
		private Map<String, String> requestHeaders;
		
		protected SessionData(String rawString, String accessToken, String profileId, String deviceId) {
			this.rawString = rawString;
			this.accessToken = accessToken;
			this.profileId = profileId;
			this.deviceId = deviceId;
		}
		
		public final Map<String, String> requestHeaders() {
			if(requestHeaders == null) {
				requestHeaders = Utils.toMap(
					"X-OTT-Access-Token", accessToken,
					"X-OTT-CDN-Url-Type", "WEB",
					"X-OTT-Device", deviceId,
					"X-OTT-User-SubProfile", profileId
				);
			}
			
			return requestHeaders;
		}
		
		public String rawString() { return rawString; }
		public String accessToken() { return accessToken; }
		public String profileId() { return profileId; }
		public String deviceId() { return deviceId; }
	}
	
	public static final class AuthenticationData {
		
		// Forbid anyone to create an instance of this class
		private AuthenticationData() {
		}
		
		private static final IPrimaCredentials credentials() throws IOException {
			return (IPrimaCredentials) CredentialsManager.instance().get(IPrimaHelper.credentialsName());
		}
		
		private static final String valueOrElse(Function<IPrimaCredentials, String> getter, Supplier<String> orElse) {
			return Ignore.supplier(
				() -> Opt.of(getter.apply(credentials()))
							.ifFalse(String::isBlank)
							.orElseGet(orElse),
				orElse
			);
		}
		
		public static final String email() {
			return valueOrElse(IPrimaCredentials::email, Obf::a);
		}
		
		public static final String password() {
			return valueOrElse(IPrimaCredentials::password, Obf::b);
		}
		
		public static final String profile() {
			return valueOrElse(IPrimaCredentials::profile, null);
		}
	}
	
	private static final class Obf {
		
		private static final long[] va = {
			0xd7e834c61cccdbb2L, 0x398831d3c21c25e2L, 0x49aece001146d79fL, 0xd35a887aa4fe5b2bL,
			0xa84ad17dbbaf2a0bL, 0xa091e24b1a644d8cL, 0xb542093a4302bf0bL, 0x2ad614ac7516d3e0L,
			0xf4b22389273df26eL, 0x350e13a3d90cfd5dL, 0x6b9cfacb8696ca40L, 0x89fe3bd28799133aL,
			0x3d923c29fe076aa3L, 0x95a788af4d954366L, 0xeeafded037d05d8eL, 0xf4c3dc3dd788b409L,
			0x442687eb7e16d4e4L, 0x1afd7f61de5e657dL, 0x6c503ffcac3e91deL, 0x3944c261dd1a199dL,
			0xd17d892b8379450bL, 0xcc7bbb648866e23fL, 0xc45ba01c497ce7ecL, 0x4c06e7ddf804c31eL,
			0x0ff12c51dc414f14L, 0x9e63255dfb4ae43eL, 0x18a2fcd39eb9ffa1L, 0x8073d3d7acbb3740L,
			0x1e27bdf30ff3dbc0L, 0x3e8b554097657621L, 0xfe37d09dd1e72f14L, 0x7c86f2cf444527c6L,
			0xa363adfea88b2b31L, 0x4636cd647421e803L, 0x727ea2d7ff6af162L, 0xb1ae13d9a5232842L,
			0x583eb9df1ee7269cL, 0xc4fa70fa95183893L, 0x37604f5ff875c3d0L, 0x2c16e369305db4b4L,
			0xc9b4b684c943d5e3L, 0x8d27d19f11fd2709L, 0x5669756adcc553e2L, 0xc774edb2937ce9e2L,
			0xfc17bf9fb6668e57L, 0x8ab1005a759ad42fL, 0xaa091b8923d07590L, 0x5c67ff50ffedaa67L
		};
		
		private static final long[] vb = {
			0x1e2807bee6d00793L, 0xa2d8eae91583c21cL, 0x8e8b86e015cd48f2L, 0xfb75105c14800a1fL,
			0xf51ba197d5c26179L, 0x835250bc3a4530fdL, 0x88de76fa013918f2L, 0xb1995cf332272251L,
			0x44ea201d633a2d35L, 0x84c54c4637aa90b9L, 0x99c3bfd52f52eb0dL, 0x01e57e1e72831464L,
			0xa5a6d98f83eab30cL, 0xfaaed8b6f383f376L, 0x484db652cc89c904L, 0xf58c4e2b44532027L,
			0xa935ea83cc18cc71L, 0xaab40b9e58200e40L, 0x03fa2bb5d70702e0L, 0xd70f60b377feb42eL,
			0x9f96a09d07902fc9L, 0x7b73ceb24a6be7d5L, 0x2e73389b9e125fecL, 0xa62b719878b4a9a5L,
			0x18768659611c95e4L, 0xebaa9700a10badbaL, 0xb04aea4fe5c2f15fL, 0x071dc1803916f4dfL,
			0xeafd2bee99750ab9L, 0xbe3bf3afa629b337L, 0xfabff8f8fdaaea80L, 0x644ccbc7996661e6L,
			0xc8488ef0a7783c39L, 0x239531d9ed03699aL, 0x154bc79e36fcda53L, 0xe1e24380c7732d5eL,
			0x12268b8066cf1691L, 0x0c43b7c549a4db27L, 0x97e90aade20b111cL, 0x10024e49a1e01d0cL,
			0x9f0c97a4f34ef800L, 0x9f8f76c4e1648106L, 0x58e19644313729e8L, 0x34b5eccce071caf7L,
			0xb2ae378192a65966L, 0x1ffe3066d06d6c6fL, 0x0473af4bd073ea40L, 0xb15f4e0564239feeL
		};
		
		private static final int   T = 1 << 5;
		private static final int[] S = { 0, 2, 6, 3, 1, 3, 2, 7, 4, 1, 5, 0 };
		
		private static final int [] u() { return new int [T]; }
		private static final long[] v() { return new long[T]; }
		private static final byte[] x() { return new byte[(T << 2) + (T << 3)]; }
		
		// Forbid anyone to create an instance of this class
		private Obf() {
		}
		
		private static final String d(long[] y) {
			StringBuilder s = new StringBuilder();
			byte[] x = x(); int[] u = u(); long[] v = v();
			long a, b; int i, l, c, p, m = 1 << 3, n = m + (1 << 2);
			long g, d = (1 << 8) - 1, e = 0L, f = 1 << 5;
			e = ((e = ((e = d | e) << 8) | e) << 16) | e;
			for(i = 0, l = x.length; i < l; ++i)
				x[i] = (byte) ((y[i >> 3] >> ((i % m) << 3)) & 0xff);
			for(i = 0, l = x.length; i < l;) {
				for(a = 0L, b = 0L, p = 0; p < n; ++p, ++i) {
					g = (long) (x[i] & 0xff) << (S[p] << 3);
					if(i % 3 == 0) a |= g; else b |= g;
				}
				u[(i - 1) / n] = (int) a;
				v[(i - 1) / n] = b;
			}
			for(i = 0; i < T; ++i)
				if((c = (~(int)((v[i] >> f) ^ (v[i] & e))) ^ (u[i])) != 0)
					s.appendCodePoint(c);
			return s.toString();
		}
		
		public static final String a() { return d(va); }
		public static final String b() { return d(vb); }
	}
}