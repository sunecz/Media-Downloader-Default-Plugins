package sune.app.mediadown.media_engine.novavoyo;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sune.app.mediadown.media_engine.novavoyo.Authenticator.AuthenticationToken;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;

public final class Connection implements AutoCloseable {
	
	private static final URI URI_HTTP_BASE = Net.uri("https://http.cms.jyxo.cz/api/v1.5/");
	private static final URI URI_WS_BASE = Net.uri("wss://ws.cms.jyxo.cz/websocket/");
	private static final long DEFAULT_TIMEOUT_MS = 5000L;
	
	private final String clientId;
	private final Device device;
	private WS ws;
	private volatile Context context;
	private AuthenticationToken authToken;
	private volatile boolean isOpen;
	
	private final Map<String, Response> responses = new ConcurrentHashMap<>();
	private final Lock lockResponse = new ReentrantLock();
	private final Condition hasResponse = lockResponse.newCondition();
	private final CountDownLatch latchInit = new CountDownLatch(1);
	
	public Connection(String clientId, Device device) {
		this.clientId = Objects.requireNonNull(clientId);
		this.device = Objects.requireNonNull(device);
	}
	
	private final JSONCollection createRequest(Request request) {
		JSONCollection data = context.json();
		data.set("context.requestId", Common.newUUID());
		
		JSONCollection payload;
		if((payload = request.payload()) != null) {
			data.set("payload", payload);
		}
		
		JSONCollection customData;
		if((customData = request.customData()) != null) {
			data.set("context.customData", JSONObject.ofString(customData.toString(true)));
		}
		
		JSONCollection playbackCapabilities;
		if((playbackCapabilities = request.playbackCapabilities()) != null) {
			data.set("playbackCapabilities", playbackCapabilities);
		}
		
		JSONCollection authorization;
		if((authorization = request.authorization()) != null) {
			data.set("authorization", authorization);
		}
		
		return data;
	}
	
	private final Response doRequest(String path, JSONCollection data) throws Exception {
		String body = data.toString(true);
		String requestId;
		
		Web.Request.Builder request = Web.Request.of(URI_HTTP_BASE.resolve(path));
		
		if(authToken != null) {
			request.header("Authorization", "Bearer " + authToken.value());
		}
		
		try(Web.Response.OfStream httpResponse = Web.requestStream(request.POST(body))) {
			if(httpResponse.statusCode() != 200) {
				throw new IllegalStateException("Non-success HTTP status code");
			}
			
			JSONCollection json = JSON.read(httpResponse.stream());
			String status = json.getString("result.status");
			
			if(!status.equals("OkAsync")) {
				throw new IllegalStateException("Error status: " + status);
			}
			
			requestId = json.getString("context.requestId");
		}
		
		if(requestId == null) {
			throw new IllegalStateException("Invalid request ID");
		}
		
		return awaitResponse(requestId);
	}
	
	private final void doOpen() {
		Web.Request req = Web.Request.of(URI_WS_BASE.resolve(clientId)).GET();
		ws = new WS(req, new WSListener());
		
		try {
			latchInit.await();
			isOpen = true;
		} catch(InterruptedException ex) {
			// Ignore
		}
	}
	
	private final void doClose() {
		ws.close();
		responses.clear();
		context = null;
		ws = null;
		latchInit.countDown();
		isOpen = false;
	}
	
	private final void parseMessage(JSONCollection json) {
		if(context == null) {
			parseInitMessage(json);
			latchInit.countDown();
		} else {
			if("Ping".equals(json.getString("schema"))) {
				sendPong();
				return; // Do not parse PING messages
			}
			
			parseResponseMessage(json);
		}
	}
	
	private final void parseInitMessage(JSONCollection json) {
		String resultStatus = json.getString("result.status");
		String resultSchema = json.getString("result.schema");
		
		if(!resultStatus.equals("Ok")) {
			throw new IllegalStateException("Init message failed");
		}
		
		if("ConnectionInitData".equals(resultSchema)) {
			String sessionId = json.getString("data.sessionId");
			String serverId = json.getString("data.serverId");
			context = new Context(device, clientId, sessionId, serverId);
		}
	}
	
	private final void parseResponseMessage(JSONCollection json) {
		String command = json.getString("command");
		JSONCollection response = json.getCollection("response");
		
		String status = response.getString("result.status");
		String requestId = response.getString("context.requestId");
		
		String key = "Ok".equals(status) ? "data" : "result";
		JSONCollection data = response.getCollection(key);
		Response responseObj = new Response(command, status, data);
		lockResponse.lock();
		
		try {
			responses.put(requestId, responseObj);
			hasResponse.signalAll();
		} finally {
			lockResponse.unlock();
		}
	}
	
	private final void sendPong() {
		ws.send("{\"schema\":\"Pong\"}");
	}
	
	public Connection open() {
		if(!isOpen) {
			synchronized(this) {
				if(!isOpen) {
					doOpen();
				}
			}
		}
		
		return this;
	}
	
	@Override
	public void close() {
		if(isOpen) {
			synchronized(this) {
				if(isOpen) {
					doClose();
				}
			}
		}
	}
	
	public void authenticate(AuthenticationToken authToken) {
		this.authToken = authToken;
	}
	
	public boolean isAuthenticated() {
		return authToken != null;
	}
	
	public Response command(String path, String schema, JSONCollection args)
			throws Exception {
		JSONCollection data = args.copy();
		data.set("schema", schema);
		JSONCollection payload = JSONCollection.ofObject("command", data);
		return request(path, payload);
	}
	
	public Response request(String path) throws Exception {
		return request(path, new Request(null, null, null, null));
	}
	
	public Response request(String path, JSONCollection payload) throws Exception {
		return request(path, new Request(payload, null, null, null));
	}
	
	public Response request(String path, JSONCollection payload,
			JSONCollection customData) throws Exception {
		return request(path, new Request(payload, customData, null, null));
	}
	
	public Response request(String path, JSONCollection payload,
			JSONCollection customData, JSONCollection playbackCapabilities) throws Exception {
		return request(path, new Request(payload, customData, playbackCapabilities, null));
	}
	
	public Response request(String path, Request request) throws Exception {
		return doRequest(path, createRequest(request));
	}
	
	public Response awaitResponse(String requestId) throws Exception {
		return awaitResponse(requestId, DEFAULT_TIMEOUT_MS);
	}
	
	public Response awaitResponse(String requestId, long timeoutMs) throws Exception {
		Response response;
		if((response = responses.remove(requestId)) != null) {
			return response;
		}
		
		if(!lockResponse.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
			return null;
		}
		
		try {
			long remainingTime = timeoutMs * 1000000L;
			
			do {
				remainingTime = hasResponse.awaitNanos(remainingTime);
				
				if((response = responses.remove(requestId)) != null) {
					return response;
				}
			} while(remainingTime > 0L);
		} finally {
			lockResponse.unlock();
		}
		
		return null;
	}
	
	private final class WSListener implements WS.Listener {
		
		@Override
		public void onOpen() {
		}
		
		@Override
		public void onClose(int statusCode, String reason) {
		}
		
		@Override
		public void onMessageReceived(JSONCollection json) {
			parseMessage(json);
		}
	}
	
	public static final class Request {
		
		private final JSONCollection payload;
		private final JSONCollection customData;
		private final JSONCollection playbackCapabilities;
		private final JSONCollection authorization;
		
		public Request(
			JSONCollection payload,
			JSONCollection customData,
			JSONCollection playbackCapabilities,
			JSONCollection authorization
		) {
			this.payload = payload;
			this.customData = customData;
			this.playbackCapabilities = playbackCapabilities;
			this.authorization = authorization;
		}
		
		public JSONCollection payload() {
			return payload;
		}
		
		public JSONCollection customData() {
			return customData;
		}
		
		public JSONCollection playbackCapabilities() {
			return playbackCapabilities;
		}
		
		public JSONCollection authorization() {
			return authorization;
		}
	}
	
	public static final class Response {
		
		private final String command;
		private final String status;
		private final JSONCollection data;
		
		public Response(String command, String status, JSONCollection data) {
			this.command = command;
			this.status = status;
			this.data = data;
		}
		
		public String command() {
			return command;
		}
		
		public JSONCollection data() {
			return data;
		}
		
		public boolean isSuccess() {
			return "Ok".equals(status);
		}
	}
}
