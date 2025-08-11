package sune.app.mediadown.server.sledovanitv;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient.Redirect;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.server.sledovanitv.Common.AuthenticationException;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.Utils;

public final class API {
	
	private static final URI URI_BASE = Net.uri("https://sledovanitv.cz/api/");
	private static final String USER_AGENT = "okhttp/4.12.0";
	private static final String APP_LANG = "cs";
	private static final String APP_VERSION = "2.81.147";
	private static final String APP_CAPABILITIES = "vast,clientvast,webvtt,adaptive2";
	private static final String DEVICE_TYPE = "androidportable";
	private static final String DEVICE_PRODUCT = "Google:Pixel 8:shiba";
	private static final String DEVICE_SERIAL = "9f14d678c4ec4706";
	private static final String QUALITY = "40"; // MOBILE_WIFI_QUALITY_DEFAULT
	
	public API() {
	}
	
	private static final boolean isJSONResponse(Web.Response response) {
		String contentType = response.headers().firstValue("content-type").orElse(null);
		return contentType != null
					&& "application/json".equals(Utils.beforeFirst(contentType, ";").trim());
	}
	
	private static final URI endpointUri(String endpoint) {
		return URI_BASE.resolve(endpoint);
	}
	
	private final API.Response doRequest(Request request) throws Exception {
		try(Web.Response.OfStream response = Web.requestStream(request)) {
			if(!isJSONResponse(response)) {
				throw new IllegalStateException("Not JSON response");
			}
			
			InputStream stream = response.stream();
			boolean isGzip = response.headers()
				.firstValue("content-encoding")
				.filter("gzip"::equalsIgnoreCase)
				.isPresent();
			
			if(isGzip) {
				stream = new GZIPInputStream(stream);
			}
			
			JSONCollection data = JSON.read(stream);
			return new API.Response(response, data);
		}
	}
	
	private final API.Response request(
		String path,
		String query,
		String sessionId
	) throws Exception {
		URI uri = endpointUri(path);
		
		if(query != null && !query.isEmpty()) {
			uri = Net.uri(uri.toString() + "?" + query);
		}
		
		Request.Builder request = Request.of(uri)
			.addHeader("accept-encoding", "gzip")
			.addCookie(Web.Cookie.builder("_nss", "1").path("/").secure(true).build())
			.userAgent(USER_AGENT)
			.followRedirects(Redirect.NEVER);
		
		if(sessionId != null) {
			request.addCookie(
				Web.Cookie.builder("PHPSESSID", sessionId).path("/").secure(true).build()
			);
		}
		
		return doRequest(request.GET());
	}
	
	private final Exception error(String message, JSONCollection data) {
		String error = data.getString("error", "Unknown error");
		
		if("not logged".equalsIgnoreCase(error)) {
			return new AuthenticationException();
		}
		
		return new IllegalStateException(
			message.contains("%s") ? String.format(message, error) : message
		);
	}
	
	public DevicePairing createPairing(String username, String password) throws Exception {
		String query = Net.queryString(
			"type", DEVICE_TYPE,
			"product", DEVICE_PRODUCT,
			"serial", DEVICE_SERIAL,
			"username", username,
			"password", password,
			"lang", APP_LANG,
			"unit", "default",
			"checkLimit", "1"
		);
		
		API.Response response = request("create-pairing", query, null);
		JSONCollection data = response.data();
		
		if(!response.isSuccess()) {
			throw error("Failed to create device pairing: %s", data);
		}
		
		String deviceId = String.valueOf(data.getLong("deviceId", 0L));
		String pairingPassword = data.getString("password");
		return new DevicePairing(deviceId, pairingPassword);
	}
	
	public Session deviceLogin(String deviceId, String pairingPassword) throws Exception {
		String query = Net.queryString(
			"deviceId", deviceId,
			"password", pairingPassword,
			"version", APP_VERSION,
			"lang", APP_LANG,
			"unit", "default",
			"capabilities", APP_CAPABILITIES
		);
		
		API.Response response = request("device-login", query, null);
		JSONCollection data = response.data();
		
		if(!response.isSuccess()) {
			throw error("Failed to login: %s", data);
		}
		
		String profileId = String.valueOf(data.getLong("activeProfileId", 0L));
		String sessionId = data.getString("PHPSESSID");
		return new Session(deviceId, profileId, sessionId);
	}
	
	public JSONCollection recordMediaSource(Session session, String recordId) throws Exception {
		String sessionId;
		if(session == null || (sessionId = session.sessionId()) == null) {
			throw new AuthenticationException("Invalid session");
		}
		
		String query = Net.queryString(
			"format", "m3u8",
			"radioFormat", "m3u8",
			"recordId", recordId,
			"drm", "widevine",
			"capabilities", APP_CAPABILITIES,
			"quality", QUALITY,
			"PHPSESSID", sessionId
		);
		
		API.Response response = request("record-timeshift", query, sessionId);
		JSONCollection data = response.data();
		
		if(!response.isSuccess()) {
			throw error("Failed to fetch media source data: %s", data);
		}
		
		return data;
	}
	
	public JSONCollection eventMediaSource(Session session, String eventId) throws Exception {
		String sessionId;
		if(session == null || (sessionId = session.sessionId()) == null) {
			throw new AuthenticationException("Invalid session");
		}
		
		String query = Net.queryString(
			"format", "m3u8",
			"radioFormat", "m3u8",
			"eventId", eventId,
			"drm", "widevine",
			"capabilities", APP_CAPABILITIES,
			"quality", QUALITY,
			"overrun", "1",
			"PHPSESSID", sessionId
		);
		
		API.Response response = request("event-timeshift", query, sessionId);
		JSONCollection data = response.data();
		
		if(!response.isSuccess()) {
			throw error("Failed to fetch media source data: %s", data);
		}
		
		return data;
	}
	
	public static final class Response {
		
		private final Web.Response response;
		private final JSONCollection data;
		
		public Response(Web.Response response, JSONCollection data) {
			this.response = response;
			this.data = data;
		}
		
		public boolean isSuccess() {
			int code = response.statusCode();
			
			if(code < 200 || code >= 300) {
				return false;
			}
			
			JSONCollection data = data();
			
			// If the status is not present, treat the response as successful
			return data.getInt("status", 1) == 1; 
		}
		
		public JSONCollection data() {
			return data;
		}
	}
	
	public static final class DevicePairing {
		
		private final String deviceId;
		private final String pairingPassword;
		
		public DevicePairing(String deviceId, String pairingPassword) {
			this.deviceId = Objects.requireNonNull(deviceId);
			this.pairingPassword = Objects.requireNonNull(pairingPassword);
		}
		
		public String deviceId() {
			return deviceId;
		}
		
		public String pairingPassword() {
			return pairingPassword;
		}
	}
	
	public static final class Session {
		
		private final String deviceId;
		private final String profileId;
		private final String sessionId;
		
		public Session(String deviceId, String profileId, String sessionId) {
			this.deviceId = Objects.requireNonNull(deviceId);
			this.profileId = Objects.requireNonNull(profileId);
			this.sessionId = Objects.requireNonNull(sessionId);
		}
		
		public String deviceId() {
			return deviceId;
		}
		
		public String profileId() {
			return profileId;
		}
		
		public String sessionId() {
			return sessionId;
		}
	}
}
