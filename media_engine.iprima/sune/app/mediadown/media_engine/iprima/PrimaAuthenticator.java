package sune.app.mediadown.media_engine.iprima;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import sune.app.mediadown.authentication.CredentialsManager;
import sune.app.mediadown.concurrent.VarLoader;
import sune.app.mediadown.media_engine.iprima.IPrimaEnginePlugin.IPrimaCredentials;
import sune.app.mediadown.media_engine.iprima.PrimaAuthenticator.Devices.Device;
import sune.app.mediadown.media_engine.iprima.PrimaAuthenticator.Profiles.Profile;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.MessageException;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.Nuxt;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.RPC;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.TranslatableException;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;

public final class PrimaAuthenticator {
	
	private static final String URL_SESSION_CREATE;
	
	private static final VarLoader<Session> session;
	private static final VarLoader<SessionData> sessionData;
	
	static {
		URL_SESSION_CREATE = "https://ucet.iprima.cz/api/session/create";
		session = VarLoader.ofChecked(PrimaAuthenticator::initSession);
		sessionData = VarLoader.ofChecked(PrimaAuthenticator::initSessionData);
	}
	
	// Forbid anyone to create an instance of this class
	private PrimaAuthenticator() {
	}
	
	private static final Session login(String email, String password) throws Exception {
		String body = Net.queryString(
			"deviceName", Devices.DEFAULT_DEVICE_NAME,
			"email", email,
			"password", password
		);
		
		try(Response.OfStream response = Web.requestStream(
				Request.of(Net.uri(URL_SESSION_CREATE)).POST(body)
		)) {
			if(response.statusCode() != 200) {
				throw new IncorrectAuthDataException();
			}
			
			return Session.parse(JSON.read(response.stream()));
		}
	}
	
	private static final Session initSession() throws Exception {
		return login(
			AuthenticationData.email(),
			AuthenticationData.password()
		);
	}
	
	private static final SessionData initSessionData() throws Exception {
		Session session = session();
		String profileId = Cached.profile().id();
		String deviceId = Cached.device().id();
		String profileTokenSecret = Profiles.profileTokenSecret();
		
		return new SessionData(
			session,
			profileId,
			deviceId,
			profileTokenSecret
		);
	}
	
	private static final Session session() throws Exception {
		return session.valueChecked();
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
			
			if(profileId == null
					|| profileId.isEmpty()
					|| profileId.equalsIgnoreCase("auto")) {
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
		
		private static final String URL_PROFILE_PAGE = "https://www.iprima.cz/profily";
		
		// Forbid anyone to create an instance of this class
		private Profiles() {
		}
		
		private static final JSONCollection findJSONCollection(
			JSONCollection collection,
			String name
		) {
			if(collection.name().equals(name)) {
				return collection;
			}
			
			return Utils.stream(collection.collectionsIterable())
						.map((c) -> findJSONCollection(c, name))
						.filter(Objects::nonNull)
						.findFirst().orElse(null);
		}
		
		public static final boolean isProfileSelectPage(URI uri) {
			return uri.toString().startsWith(URL_PROFILE_PAGE);
		}
		
		public static final List<Profile> extractProfiles(String content) throws Exception {
			Nuxt nuxt = Nuxt.extract(content);
			
			if(nuxt == null) {
				throw new IllegalStateException("Unable to Nuxt extract information");
			}
			
			List<Profile> profiles = new ArrayList<>();
			JSONCollection data = findJSONCollection(nuxt.state(), "profiles");
			
			for(JSONCollection profile : data.collectionsIterable()) {
				String id = profile.getString("ulid");
				String name = profile.getString("name");
				profiles.add(new Profile(id, name));
			}
			
			return profiles;
		}
		
		public static final List<Profile> list() throws Exception {
			Session session = session();
			Request request = Request.of(Net.uri(URL_PROFILE_PAGE))
				.cookies(session.cookies()) // Must include the session cookies
				.GET();
			
			return extractProfiles(Web.request(request).body());
		}
		
		public static final String profileTokenSecret() throws Exception {
			try(Response.OfString response = Web.request(
					Request.of(Net.uri(URL_PROFILE_PAGE)).GET()
			)) {
				String body = response.body();
				int index = body.indexOf("__NUXT__.config");
				
				if(index < 0) {
					throw new IllegalStateException("Cannot obtain NUXT config");
				}
				
				JSONCollection config = JavaScript.readObject(
					Utils.bracketSubstring(body, '{', '}', false, index, body.length())
				);
				
				return config.getString("public.profileTokenSecret");
			}
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
		
		private static final String DEFAULT_DEVICE_NAME = "Windows Chrome";
		private static final String DEFAULT_DEVICE_TYPE = "WEB";
		
		// Forbid anyone to create an instance of this class
		private Devices() {
		}
		
		private static final String accessToken() throws Exception {
			return session().accessToken();
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
			
			if(RPC.isError(response)) {
				throw new MessageException(response.getString("error.message"));
			}
			
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
			
			if(RPC.isError(response)) {
				throw new MessageException(response.getString("error.message"));
			}
			
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
	
	private static final class Session {
		
		private final String sessionId;
		private final String ssoToken;
		private final String accessToken;
		private final String refreshToken;
		private List<HttpCookie> cookies;
		
		protected Session(
			String sessionId,
			String ssoToken,
			String accessToken,
			String refreshToken
		) {
			this.sessionId = sessionId;
			this.ssoToken = ssoToken;
			this.accessToken = accessToken;
			this.refreshToken = refreshToken;
		}
		
		public static final Session parse(JSONCollection json) {
			String sessionId = json.getString("sessionId");
			String ssoToken = Utils.base64Encode(json.toString(true));
			String accessToken = json.getString("accessToken.value");
			String refreshToken = json.getString("refreshToken.valueEncrypted");

			return new Session(
				sessionId,
				ssoToken,
				accessToken,
				refreshToken
			);
		}
		
		public List<HttpCookie> cookies() {
			if(cookies == null) {
				cookies = List.of(
					Web.Cookie.builder("prima_sso_token", ssoToken)
						.path("/")
						.maxAge(365L * 24L * 3600L) // 1 year in seconds
						.build()
				);
			}
			
			return cookies;
		}
		
		public String sessionId() { return sessionId; }
		public String accessToken() { return accessToken; }
		
		@SuppressWarnings("unused")
		public String ssoToken() { return ssoToken; }
		@SuppressWarnings("unused")
		public String refreshToken() { return refreshToken; }
	}
	
	public static final class SessionData {
		
		private final Session session;
		private final String profileId;
		private final String deviceId;
		private final String profileTokenSecret;
		private Map<String, String> requestHeaders;
		
		protected SessionData(
			Session session,
			String profileId,
			String deviceId,
			String profileTokenSecret
		) {
			this.session = session;
			this.profileId = profileId;
			this.deviceId = deviceId;
			this.profileTokenSecret = profileTokenSecret;
		}
		
		// Prima+ introduced a new required argument when obtaining media sources. This argument
		// (select token) is just a Base64-encoded information about the selected profile with
		// a session ID (usually null) and an expiration time in Unix epoch (usually 45 minutes
		// in the future). All is wrapped in a JWT token that is signed with HMAC SHA-256.
		// The signature key seems to be static and is present in the NUXT config object.
		// We can simplify the whole process to hardcode some known stuff and just find the sign
		// key just in case, when they decide to change it or if it is actually not always static.
		private final String profileSelectToken() {
			final long expSeconds = 45 * 60; // 45 minutes
			JSONCollection profileToken = JSONCollection.ofObject(
				"profileId", JSONObject.ofString(profileId),
				"sessionId", JSONObject.ofString(session.sessionId()),
				"exp", JSONObject.ofLong(System.currentTimeMillis() / 1000L + expSeconds)
			);
			
			String header = Utils.base64URLEncode("{\"alg\":\"HS256\"}");
			String body = Utils.base64URLEncode(profileToken.toString(true));
			String signature = Utils.base64URLEncodeRawAsString(
				Crypto.hmac256(header + '.' + body, profileTokenSecret)
			);
			
			return header + '.' + body + '.' + signature;
		}
		
		public final Map<String, String> requestHeaders() {
			if(requestHeaders == null) {
				requestHeaders = Utils.toMap(
					"X-OTT-Access-Token", session.accessToken(),
					"X-OTT-CDN-Url-Type", "WEB",
					"X-OTT-Device", deviceId,
					"X-OTT-User-SubProfile", profileId,
					// The new required argument must be passed in a Cookie.
					"Cookie", "prima_profile_select_token=" + profileSelectToken()
				);
			}
			
			return requestHeaders;
		}
		
		public String accessToken() { return session.accessToken(); }
		public String profileId() { return profileId; }
		public String deviceId() { return deviceId; }
		
		private static final class Crypto {
			
			private static final String HMAC_SHA256 = "HmacSHA256";
			
			private Crypto() {
			}
			
			public static final byte[] hmac256(String data, String key) {
				try {
					SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), HMAC_SHA256);
					Mac mac = Mac.getInstance(HMAC_SHA256);
					mac.init(keySpec);
					return mac.doFinal(data.getBytes());
				} catch(NoSuchAlgorithmException | InvalidKeyException ex) {
					throw new IllegalStateException("Cannot sign data", ex); // Should not happen
				}
			}
		}
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