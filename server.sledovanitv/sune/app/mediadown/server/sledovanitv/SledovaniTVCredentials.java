package sune.app.mediadown.server.sledovanitv;

import sune.app.mediadown.Shared;
import sune.app.mediadown.authentication.UsernameCredentials;

public final class SledovaniTVCredentials extends UsernameCredentials {
	
	public SledovaniTVCredentials() {
		defineFields(
			"deviceId", bytes(""),
			"profileId", bytes("auto"),
			"sessionId", bytes("")
		);
	}
	
	public SledovaniTVCredentials(
		byte[] username,
		byte[] password,
		byte[] deviceId,
		byte[] profileId,
		byte[] sessionId
	) {
		super(username, password);
		defineFields(
			"deviceId", deviceId,
			"profileId", profileId,
			"sessionId", sessionId
		);
	}
	
	public SledovaniTVCredentials(
		String username,
		String password,
		String deviceId,
		String profileId,
		String sessionId
	) {
		this(
			bytes(username),
			bytes(password),
			bytes(deviceId),
			bytes(profileId),
			bytes(sessionId)
		);
	}
	
	private static final byte[] bytes(String data) {
		return data == null ? new byte[0] : data.getBytes(Shared.CHARSET);
	}
	
	private static final String string(byte[] data) {
		return data == null ? "" : new String(data, Shared.CHARSET);
	}
	
	public String deviceId() {
		return isInitialized() ? string(get("deviceId")) : null;
	}
	
	public String profileId() {
		return isInitialized() ? string(get("profileId")) : null;
	}
	
	public String sessionId() {
		return isInitialized() ? string(get("sessionId")) : null;
	}
}
