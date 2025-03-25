package sune.app.mediadown.media_engine.novavoyo;

import java.util.Objects;

import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;

public final class Context {
	
	private final Device device;
	private final String clientId;
	private final String sessionId;
	private final String serverId;
	
	public Context(Device device, String clientId, String sessionId, String serverId) {
		this.device = Objects.requireNonNull(device);
		this.clientId = Objects.requireNonNull(clientId);
		this.sessionId = Objects.requireNonNull(sessionId);
		this.serverId = Objects.requireNonNull(serverId);
	}
	
	public JSONCollection json() {
		return JSONCollection.ofObject(
			"deviceInfo", device.json(),
			"capabilities", JSONCollection.ofObject(
				"async", JSONObject.ofString("websockets")
			),
			"context", JSONCollection.ofObject(
				"clientId", JSONObject.ofString(clientId),
				"sessionId", JSONObject.ofString(sessionId),
				"serverId", JSONObject.ofString(serverId)
			)
		);
	}
}
