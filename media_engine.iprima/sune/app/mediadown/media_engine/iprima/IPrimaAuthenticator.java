package sune.app.mediadown.media_engine.iprima;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import sune.app.mediadown.Shared;
import sune.app.mediadown.configuration.Configuration;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.util.Property;
import sune.app.mediadown.util.Reflection2;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Web.PostRequest;
import sune.app.mediadown.util.Web.Response;
import sune.app.mediadown.util.Web.StringResponse;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDF;

final class IPrimaAuthenticator {
	
	private static final URL URL_OAUTH_LOGIN;
	private static final URL URL_OAUTH_TOKEN;
	private static final URL URL_OAUTH_AUTHORIZE;
	private static final URL URL_LOGIN;
	
	private static IPrimaAuthenticator.SessionData SESSION_DATA;
	
	static {
		URL_OAUTH_LOGIN = Utils.url("https://auth.iprima.cz/oauth2/login");
		URL_OAUTH_TOKEN = Utils.url("https://auth.iprima.cz/oauth2/token");
		URL_OAUTH_AUTHORIZE = Utils.url("https://auth.iprima.cz/oauth2/authorize");
		URL_LOGIN = Utils.url("https://www.iprima.cz/user-login-proxy");
	}
	
	@FunctionalInterface private static interface _Callback<P, R> { R call(P param) throws Exception; }
	private static final <C extends AutoCloseable, R> R tryAndClose(C closeable, IPrimaAuthenticator._Callback<C, R> action) throws Exception {
		try(closeable) { return action.call(closeable); }
	}
	
	private static final String loginOAuth(String email, String password) throws Exception {
		StringResponse response = Web.request(new GetRequest(URL_OAUTH_LOGIN, Shared.USER_AGENT));
		String csrfToken = Utils.parseDocument(response.content).selectFirst("input[name='_csrf_token']").val();
		Map<String, String> params = Utils.toMap("_email", email, "_password", password, "_csrf_token", csrfToken);
		response = Web.request(new PostRequest(URL_OAUTH_LOGIN, Shared.USER_AGENT, params));
		HttpURLConnection con = Reflection2.getField(Response.class, response, "connection");
		return Utils.urlParams(con.getURL().toExternalForm()).getOrDefault("code", null);
	}
	
	private static final IPrimaAuthenticator.SessionTokens sessionTokens(String code) throws Exception {
		Map<String, String> params = Utils.toMap(
			"scope", "openid+email+profile+phone+address+offline_access",
			"client_id", "prima_sso",
			"grant_type", "authorization_code",
			"code", code,
			"redirect_uri", "https://auth.iprima.cz/sso/auth-check");
		return tryAndClose(Web.requestStream(new PostRequest(URL_OAUTH_TOKEN, Shared.USER_AGENT, params)),
		                   response -> SessionTokens.parse(SSDF.readJSON(response.stream)));
	}
	
	private static final String authorize(IPrimaAuthenticator.SessionTokens tokens) throws Exception {
		Map<String, String> params = Utils.toMap(
			"response_type", "token_code",
			"client_id", "sso_token",
			"token", tokens.tokenDataString());
		URL url = Utils.url(URL_OAUTH_AUTHORIZE.toExternalForm() + '?' + Utils.joinURLParams(params));
		return tryAndClose(Web.requestStream(new GetRequest(url, Shared.USER_AGENT)),
		                   response -> SSDF.readJSON(response.stream).getDirectString("code", null));
	}
	
	private static final boolean finishLogin(String code) throws Exception {
		Map<String, String> params = Utils.toMap(
			"auth_token_code", code,
			"redirect_uri", "https://www.iprima.cz/");
		URL url = Utils.url(URL_LOGIN.toExternalForm() + '?' + Utils.joinURLParams(params));
		return tryAndClose(Web.requestStream(new GetRequest(url, Shared.USER_AGENT, null, null, false)),
		                   response -> response.code == 302);
	}
	
	public static final IPrimaAuthenticator.SessionData getSessionData() throws Exception {
		if(SESSION_DATA == null) {
			IPrimaAuthenticator.SessionTokens tokens = sessionTokens(loginOAuth(
				AuthenticationData.email(),
				AuthenticationData.password()
			));
			boolean success = finishLogin(authorize(tokens));
			// The device ID must be obtained AFTER logging in
			String deviceId = DeviceManager.deviceId();
			SESSION_DATA = success ? new SessionData(tokens.accessToken(), deviceId) : null;
		}
		return SESSION_DATA;
	}
	
	private static final class DeviceManager {
		
		private static final String DEVICE_NAME = "Media Downloader";
		private static final String DEVICE_TYPE = "WEB";
		
		private static final String URL_LIST_DEVICES = "https://auth.iprima.cz/user/zarizeni";
		private static final String URL_ADD_DEVICE = "https://www.iprima.cz/iprima-api/PlayApiProxy/Proxy/AddNewUserSlot";
		
		private static final String SELECTOR_DEVICES = "#devices > .item script";
		private static final Pattern REGEX_DEVICE = Pattern.compile("\"data\":\\s*(\\{[^\\}]+\\})");
		
		private static String deviceId; // For caching purposes
		
		private static final List<Device> listDevices() throws Exception {
			List<Device> devices = new ArrayList<>();
			Document document = Utils.document(URL_LIST_DEVICES);
			for(Element elDevice : document.select(SELECTOR_DEVICES)) {
				Matcher matcher = REGEX_DEVICE.matcher(elDevice.html());
				if(matcher.find()) {
					SSDCollection deviceData = SSDF.readJSON(matcher.group(1));
					devices.add(Device.fromJSON(deviceData));
				}
			}
			return devices;
		}
		
		// Taken from: https://authstatic.primacdn.cz/sso/device_id.js (function: generateDid)
		private static final String generateDeviceId() {
			Property<Long> d = new Property<>(System.nanoTime());
			return Utils.replaceAll("[xy]", "d-xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx", (match) -> {
				int r = (int) ((d.getValue() + (int) (Math.random() * 16.0)) % 16L);
				d.setValue((long) Math.floor(d.getValue() / 16.0));
				return Integer.toHexString(match.group(0).equals("x") ? r : (r & 0x3 | 0x8));
			});
		}
		
		private static final Device createDevice(String id, String type, String name) throws Exception {
			Map<String, String> params = Utils.toMap("slotType", type, "title", name, "deviceUID", id);
			Map<String, String> headers = Utils.toMap("Referer", "https://prima.iprima.cz/", "X-Requested-With", "XMLHttpRequest");
			StringResponse response = Web.request(new PostRequest(Utils.url(URL_ADD_DEVICE), Shared.USER_AGENT, params, null, headers));
			if(response.code != 200)
				throw new IllegalStateException("Unable to create a new WEB device. Response: " + response.content);
			SSDCollection data = SSDF.readJSON(response.content);
			String slotId = data.getDirectString("slotId");
			return new Device(slotId, type, name);
		}
		
		private static final Device createDevice() throws Exception {
			return createDevice(generateDeviceId(), DEVICE_TYPE, DEVICE_NAME);
		}
		
		public static final String deviceId() throws Exception {
			if(deviceId == null) {
				Device device = listDevices().stream()
					.filter((d) -> d.type().equals(DEVICE_TYPE))
					.findFirst().orElse(null);
				if(device == null)
					device = createDevice();
				deviceId = device.id();
			}
			return deviceId;
		}
		
		private static final class Device {
			
			private final String slotId;
			private final String slotType;
			private final String slotName;
			
			private Device(String slotId, String slotType, String slotName) {
				this.slotId = slotId;
				this.slotType = slotType;
				this.slotName = slotName;
			}
			
			public static final Device fromJSON(SSDCollection data) {
				String slotId = data.getDirectString("slotId");
				String slotType = data.getDirectString("slotType");
				String slotName = data.getDirectString("slotName");
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
		
		private final String accessToken;
		private final String refreshToken;
		
		private SessionTokens(String accessToken, String refreshToken) {
			this.accessToken = accessToken;
			this.refreshToken = refreshToken;
		}
		
		public static final IPrimaAuthenticator.SessionTokens parse(SSDCollection json) {
			return new SessionTokens(json.getDirectString("access_token"),
			                         json.getDirectString("refresh_token"));
		}
		
		public final String tokenDataString() {
			SSDCollection data = SSDCollection.empty();
			data.setDirect("access_token", accessToken);
			data.setDirect("refresh_token", refreshToken);
			return Utils.base64URLEncode(data.toJSON(true));
		}
		
		public final String accessToken() {
			return accessToken;
		}
	}
	
	public static final class SessionData {
		
		private final String accessToken;
		private final String deviceId;
		private Map<String, String> requestHeaders;
		
		public SessionData(String accessToken, String deviceId) {
			this.accessToken = accessToken;
			this.deviceId = deviceId;
		}
		
		public final Map<String, String> requestHeaders() {
			if(requestHeaders == null) {
				requestHeaders = Utils.toMap(
					"X-OTT-Access-Token", accessToken,
					"X-OTT-CDN-Url-Type", "WEB",
					"X-OTT-Device", deviceId
				);
			}
			return requestHeaders;
		}
	}
	
	public static final class AuthenticationData {
		
		// Forbid anyone to create an instance of this class
		private AuthenticationData() {
		}
		
		private static final Configuration configuration() {
			return IPrimaHelper.configuration();
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
			0xfd1b7124731ffb21L, 0x73ddd206ab1dc618L, 0xf81abf6454f0d85aL, 0x1d745b9bb926ad37L, 
			0x03be88aae686e24aL, 0xd7d1fbeb9ec962d5L, 0x8b18188c6c28cfa5L, 0x65a6b9bc930d7ea8L, 
			0x21c9104c16e08c26L, 0xfc5a5a9c59e54088L, 0x67ad2e9ffbfb98e8L, 0x5c7863531e7c86e4L, 
			0x842b86fffdf02483L, 0xcee4e4b28536368eL, 0x224e8a01daffeb3bL, 0x095f72b08467c7f5L, 
			0x905cb4a0377e3151L, 0x456edb754817274aL, 0x841fb386c8c42469L, 0x04732ef57ca6df8bL, 
			0x39ed0a5748a2b318L, 0xa2c8121f4f6c5b52L, 0x489e5af527aa4aefL, 0xb3fab0cb7f3bc8b5L, 
			0xf8ad7fe27885d7f7L, 0x0c084fa8b8eaf7f0L, 0x8d4fc7bff1b80277L, 0xd0bea7ab885110acL, 
			0x475a8f39dd3165e3L, 0xece3764b812a396aL, 0x39b9ffd239c08642L, 0x3104cd803a9bb6eeL, 
			0xa425bdb7cc360267L, 0x1166949d7a0198c0L, 0x5e48d2dd6c20427dL, 0xda42d99b6265c364L, 
			0xaba0a464f097c852L, 0x3a9d61474b99029cL, 0xea626e529d0358f3L, 0xf7e195dc9da1bf81L, 
			0xa5b031a46fdefd11L, 0x8b36ccd0b07eea05L, 0x5117695dc752baa4L, 0xb248a15af5e240aeL, 
			0x314def943416795dL, 0x3864bc1f7bf26995L, 0x789a362583e101e9L, 0xb251f8685453d356L
		};
		
		private static final long[] vb = {
			0xeaee711364e4f5aeL, 0xb3ea9e5a40997562L, 0xb685dc49cf8b83a6L, 0x6f7d5727c7e16356L, 
			0xa0c88e9d3f14ccc0L, 0xfd0632ab9fb9c0cbL, 0x3be02189e5fae51dL, 0x165f9bc5d542347fL, 
			0x2f29fa41d23b3b2cL, 0x52e02c2181f6e954L, 0x1865b8f774e03e8eL, 0x6a45821ba4224338L, 
			0xe960a2f3b4df40faL, 0x41fa0e16dce6ea9cL, 0x348d2e90460bf85cL, 0x9a25b5e2d06bb12aL, 
			0xeccb2a56e43d205aL, 0xe214fa78d01ec311L, 0x145201f1ea5bf6d4L, 0xe404ee0e7aaaa401L, 
			0x3efb5dfd46155d59L, 0xb21d045449b456afL, 0xc563e5d1e83992d2L, 0x640c8700a1799b74L, 
			0x976436d65e049f6eL, 0x654fe6be585a73c9L, 0xec9b6aadff56650eL, 0x39d44d488bdaf131L, 
			0xe9cec1179aca7d54L, 0x4dc408a508f01e33L, 0xb3b0a716eb69263eL, 0x5e750f10234ba2e2L, 
			0x80a0ed6f2b858ab2L, 0x38cd242be391a3f0L, 0xfe077be958b36757L, 0xe3fde3f5648365e1L, 
			0x31fe74c1ba8180efL, 0x2df24e3e0d86b81dL, 0x601f33a1414393d3L, 0x0a5466cb938c27a1L, 
			0x8ee6d69d47122619L, 0xce6afeacc6cfb76bL, 0x44532b10903d91dfL, 0x511fab5949a24d69L, 
			0x96989c304a4be4fbL, 0x1027e82b0768b0ebL, 0xd67b399dce8652daL, 0x9936f4fb8abda33dL
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