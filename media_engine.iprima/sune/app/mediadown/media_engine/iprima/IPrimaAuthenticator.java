package sune.app.mediadown.media_engine.iprima;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import sune.app.mediadown.Shared;
import sune.app.mediadown.configuration.Configuration;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.media_engine.iprima.IPrimaAuthenticator.ProfileManager.Profile;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.Property;
import sune.app.mediadown.util.Reflection2;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Web.PostRequest;
import sune.app.mediadown.util.Web.Response;
import sune.app.mediadown.util.Web.StreamResponse;
import sune.app.mediadown.util.Web.StringResponse;
import sune.util.ssdf2.SSDCollection;

public final class IPrimaAuthenticator {
	
	private static final String URL_OAUTH_LOGIN;
	private static final String URL_OAUTH_TOKEN;
	private static final String URL_OAUTH_AUTHORIZE;
	private static final String URL_USER_AUTH;
	private static final String URL_PROFILE_SELECT;
	
	private static SessionData SESSION_DATA;
	
	static {
		URL_OAUTH_LOGIN = "https://auth.iprima.cz/oauth2/login";
		URL_OAUTH_TOKEN = "https://auth.iprima.cz/oauth2/token";
		URL_OAUTH_AUTHORIZE = "https://auth.iprima.cz/oauth2/authorize";
		URL_USER_AUTH = "https://www.iprima.cz/?auth_token_code=%{code}s";
		URL_PROFILE_SELECT = "https://auth.iprima.cz/user/profile-select-perform/%{profile_id}s?continueUrl=/user/login";
	}
	
	@FunctionalInterface private static interface _Callback<P, R> { R call(P param) throws Exception; }
	private static final <C extends AutoCloseable, R> R tryAndClose(C closeable, _Callback<C, R> action) throws Exception {
		try(closeable) { return action.call(closeable); }
	}
	
	private static final URL responseUrl(Response response) {
		return Reflection2.<HttpURLConnection>getField(Response.class, response, "connection").getURL();
	}
	
	private static final String loginOAuth(String email, String password) throws Exception {
		StringResponse response = Web.request(new GetRequest(Utils.url(URL_OAUTH_LOGIN), Shared.USER_AGENT));
		String csrfToken = Utils.parseDocument(response.content).selectFirst("input[name='_csrf_token']").val();
		Map<String, String> params = Map.of("_email", email, "_password", password, "_csrf_token", csrfToken);
		response = Web.request(new PostRequest(Utils.url(URL_OAUTH_LOGIN), Shared.USER_AGENT, params));
		return selectConfiguredProfile(response);
	}
	
	private static final String selectProfile(String profileId) throws Exception {
		URL url = Utils.url(Utils.format(URL_PROFILE_SELECT, "profile_id", profileId));
		StreamResponse response = Web.requestStream(new GetRequest(url, Shared.USER_AGENT));
		String responseUrl = responseUrl(response).toExternalForm();
		return Utils.urlParams(responseUrl).getOrDefault("code", null);
	}
	
	private static final List<Profile> profiles(StringResponse response) throws Exception {
		String url = responseUrl(response).toExternalForm();
		return ProfileManager.isProfileSelectPage(url)
					? ProfileManager.extractProfiles(response.content)
					: ProfileManager.profiles();
	}
	
	private static final String selectConfiguredProfile(StringResponse response) throws Exception {
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
		
		Map<String, String> params = Map.of(
			"scope", "openid+email+profile+phone+address+offline_access",
			"client_id", "prima_sso",
			"grant_type", "authorization_code",
			"code", code,
			"redirect_uri", "https://auth.iprima.cz/sso/auth-check"
		);
		
		return tryAndClose(Web.requestStream(new PostRequest(Utils.url(URL_OAUTH_TOKEN), Shared.USER_AGENT, params)),
		                   (response) -> SessionTokens.parse(JSON.read(response.stream)));
	}
	
	private static final String authorize(SessionTokens tokens) throws Exception {
		Map<String, String> params = Map.of(
			"response_type", "token_code",
			"client_id", "sso_token",
			"token", tokens.tokenDataString()
		);
		
		URL url = Utils.url(URL_OAUTH_AUTHORIZE + '?' + Utils.joinURLParams(params));
		return tryAndClose(Web.requestStream(new GetRequest(url, Shared.USER_AGENT)),
		                   (response) -> JSON.read(response.stream).getDirectString("code", null));
	}
	
	private static final boolean userAuth(String code) throws Exception {
		if(code == null) {
			return false;
		}
		
		URL url = Utils.url(Utils.format(URL_USER_AUTH, "code", code));
		return tryAndClose(Web.requestStream(new GetRequest(url, Shared.USER_AGENT, null, null, false)),
		                   (response) -> response.code == 302);
	}
	
	private static final Configuration configuration() {
		return IPrimaHelper.configuration();
	}
	
	public static final SessionData getSessionData() throws Exception {
		if(SESSION_DATA == null) {
			SessionTokens tokens = sessionTokens(loginOAuth(
				AuthenticationData.email(),
				AuthenticationData.password()
			));
			boolean success = userAuth(authorize(tokens));
			
			if(success) {
				SESSION_DATA = new SessionData(
					tokens.rawString(), tokens.accessToken(),
					// The device ID must be obtained AFTER logging in
					DeviceManager.deviceId(),
					ProfileManager.profiles().get(0).id()
				);
			}
		}
		
		return SESSION_DATA;
	}
	
	public static final class ProfileManager {
		
		private static final String URL_PROFILE_PAGE = "https://auth.iprima.cz/user/profile-select";
		
		private static final String SELECTOR_PROFILE = ".profile-select";
		
		private static List<Profile> profiles;
		
		public static final boolean isProfileSelectPage(String url) {
			return url.startsWith(URL_PROFILE_PAGE);
		}
		
		public static final List<Profile> extractProfiles(String content) throws Exception {
			List<Profile> profiles = new ArrayList<>();
			Document document = Utils.parseDocument(content);
			
			for(Element elButton : document.select(SELECTOR_PROFILE)) {
				String id = elButton.attr("data-identifier");
				String name = elButton.selectFirst(".card-body").text();
				profiles.add(new Profile(id, name));
			}
			
			return profiles;
		}
		
		private static final List<Profile> listProfiles() throws Exception {
			return extractProfiles(Web.request(new GetRequest(Utils.url(URL_PROFILE_PAGE), Shared.USER_AGENT)).content);
		}
		
		public static final List<Profile> profiles() throws Exception {
			if(profiles == null) {
				profiles = listProfiles();
			}
			
			return profiles;
		}
		
		public static final class Profile {
			
			private final String id;
			private final String name;
			
			protected Profile(String id, String name) {
				this.id = Objects.requireNonNull(id);
				this.name = Objects.requireNonNull(name);
			}
			
			public String id() {
				return id;
			}
			
			public String name() {
				return name;
			}
		}
	}
	
	public static final class DeviceManager {
		
		private static final String DEVICE_NAME = "Media Downloader";
		private static final String DEVICE_TYPE = "WEB";
		
		private static final String URL_LIST_DEVICES = "https://auth.iprima.cz/user/zarizeni";
		private static final String URL_ADD_DEVICE = "https://prima.iprima.cz/iprima-api/PlayApiProxy/Proxy/AddNewUserSlot";
		private static final String URL_REMOVE_DEVICE = "https://auth.iprima.cz/user/zarizeni/removeSlot";
		
		private static final String SELECTOR_DEVICES = "#main-content > div:nth-child(2) .container table tr td:last-child > button";
		
		private static String deviceId; // For caching purposes
		
		private static final List<Device> listDevices() throws Exception {
			List<Device> devices = new ArrayList<>();
			Document document = Utils.document(URL_LIST_DEVICES);
			
			for(Element elButton : document.select(SELECTOR_DEVICES)) {
				devices.add(Device.fromRemoveButton(elButton));
			}
			
			return devices;
		}
		
		// Taken from: https://authstatic.primacdn.cz/sso/device_id.js (function: generateDid)
		private static final String generateDeviceId() {
			Property<Long> d = new Property<>(System.nanoTime());
			return Regex.of("[xy]").replaceAll("d-xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx", (match) -> {
				int r = (int) ((d.getValue() + (int) (Math.random() * 16.0)) % 16L);
				d.setValue((long) Math.floor(d.getValue() / 16.0));
				return Integer.toHexString(match.group(0).equals("x") ? r : (r & 0x3 | 0x8));
			});
		}
		
		public static final Device createDevice(String id, String type, String name) throws Exception {
			Map<String, String> params = Map.of("slotType", type, "title", name, "deviceUID", id);
			Map<String, String> headers = Map.of("Referer", "https://prima.iprima.cz/", "X-Requested-With", "XMLHttpRequest");
			PostRequest request = new PostRequest(Utils.url(URL_ADD_DEVICE), Shared.USER_AGENT, params, null, headers);
			
			// It is actually enough to just to call the endpoint even if it returns errors,
			// so ignore them, if there are any.
			try(StreamResponse response = Web.requestStream(request)) {
				return new Device(id, type, name);
			}
		}
		
		public static final boolean removeDevice(String id) throws Exception {
			SSDCollection json = SSDCollection.empty();
			json.setDirect("slotType", "WEB");
			json.setDirect("slotId", id);
			String body = json.toJSON(true);
			Map<String, String> headers = Map.of("Referer", "https://prima.iprima.cz/");
			PostRequest request = new PostRequest(Utils.url(URL_REMOVE_DEVICE), Shared.USER_AGENT, null, null, headers,
				true, null, -1L, -1L, 5000, body);
			try(StreamResponse response = Web.requestStream(request)) {
				return response.code == 200;
			}
		}
		
		private static final Device createDevice() throws Exception {
			return createDevice(generateDeviceId(), DEVICE_TYPE, DEVICE_NAME);
		}
		
		public static final String deviceId() throws Exception {
			if(deviceId == null) {
				Device device = listDevices().stream()
					.filter((d) -> d.type().equals(DEVICE_TYPE))
					.findFirst().orElse(null);
				
				if(device == null) {
					device = createDevice();
				}
				
				deviceId = device.id();
			}
			
			return deviceId;
		}
		
		public static final class Device {
			
			private final String slotId;
			private final String slotType;
			private final String slotName;
			
			private Device(String slotId, String slotType, String slotName) {
				this.slotId = slotId;
				this.slotType = slotType;
				this.slotName = slotName;
			}
			
			public static final Device fromRemoveButton(Element button) {
				String slotId = button.attr("data-item-id");
				String slotType = button.attr("data-item-type");
				String slotName = button.attr("data-item-label");
				return new Device(slotId, slotType, slotName);
			}
			
			public String id() {
				return slotId;
			}
			
			public String type() {
				return slotType;
			}
			
			@SuppressWarnings("unused")
			public String name() {
				return slotName;
			}
		}
	}
	
	private static final class SessionTokens {
		
		private final String rawString;
		private final String accessToken;
		private final String refreshToken;
		
		private SessionTokens(String rawString, String accessToken, String refreshToken) {
			this.rawString = rawString;
			this.accessToken = accessToken;
			this.refreshToken = refreshToken;
		}
		
		public static final SessionTokens parse(SSDCollection json) {
			return new SessionTokens(json.toJSON(true), json.getDirectString("access_token"),
				json.getDirectString("refresh_token"));
		}
		
		public final String tokenDataString() {
			SSDCollection data = SSDCollection.empty();
			data.setDirect("access_token", accessToken);
			data.setDirect("refresh_token", refreshToken);
			return Utils.base64URLEncode(data.toJSON(true));
		}
		
		public String rawString() {
			return rawString;
		}
		
		public final String accessToken() {
			return accessToken;
		}
	}
	
	public static final class SessionData {
		
		private final String rawString;
		private final String accessToken;
		private final String deviceId;
		private final String profileId;
		private Map<String, String> requestHeaders;
		
		public SessionData(String rawString, String accessToken, String deviceId, String profileId) {
			this.rawString = rawString;
			this.accessToken = accessToken;
			this.deviceId = deviceId;
			this.profileId = profileId;
		}
		
		public final Map<String, String> requestHeaders() {
			if(requestHeaders == null) {
				requestHeaders = Map.of(
					"X-OTT-Access-Token", accessToken,
					"X-OTT-CDN-Url-Type", "WEB",
					"X-OTT-Device", deviceId
				);
			}
			return requestHeaders;
		}
		
		public String rawString() {
			return rawString;
		}
		
		public String accessToken() {
			return accessToken;
		}
		
		public String deviceId() {
			return deviceId;
		}
		
		public String profileId() {
			return profileId;
		}
	}
	
	public static final class AuthenticationData {
		
		// Forbid anyone to create an instance of this class
		private AuthenticationData() {
		}
		
		private static final <T> T valueOrElse(String propertyName, Supplier<T> orElse) {
			return Optional.<ConfigurationProperty<T>>ofNullable(configuration().property(propertyName))
						.map(ConfigurationProperty::value).orElseGet(orElse);
		}
		
		public static final boolean areDefaultDataUsed() {
			return valueOrElse("useDefaultAuthData", () -> true);
		}
		
		public static final String defaultEmail() {
			return Obf.a();
		}
		
		public static final String defaultPassword() {
			return Obf.b();
		}
		
		public static final String email() {
			return areDefaultDataUsed() ? defaultEmail() : valueOrElse("authData_email", AuthenticationData::defaultEmail);
		}
		
		public static final String password() {
			return areDefaultDataUsed() ? defaultPassword() : valueOrElse("authData_password", AuthenticationData::defaultPassword);
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