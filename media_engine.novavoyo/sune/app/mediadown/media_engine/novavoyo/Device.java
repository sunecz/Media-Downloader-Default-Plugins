package sune.app.mediadown.media_engine.novavoyo;

import java.util.Objects;

import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;

public final class Device {
	
	private final String type;
	private final String appVersion;
	private final String manufacturer;
	private final String os;
	
	public Device(String type, String appVersion, String manufacturer, String os) {
		this.type = Objects.requireNonNull(type);
		this.appVersion = Objects.requireNonNull(appVersion);
		this.manufacturer = Objects.requireNonNull(manufacturer);
		this.os = Objects.requireNonNull(os);
	}
	
	public JSONCollection json() {
		return JSONCollection.ofObject(
			"deviceType", JSONObject.ofString(type),
			"appVersion", JSONObject.ofString(appVersion),
			"deviceManufacturer", JSONObject.ofString(manufacturer),
			"deviceOs", JSONObject.ofString(os)
		);
	}
}
