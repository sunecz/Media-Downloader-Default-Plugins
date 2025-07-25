package sune.app.mediadown.media_engine.novavoyo;

import sune.app.mediadown.authentication.CredentialsUtils;
import sune.app.mediadown.authentication.EmailCredentials;

public final class OneplayCredentials extends EmailCredentials {
	
	public OneplayCredentials() {
		defineFields(
			"profileId", "auto",
			"profilePin", "",
			"authToken", "",
			"deviceId", ""
		);
	}
	
	public OneplayCredentials(
		byte[] email,
		byte[] password,
		byte[] profileId,
		byte[] profilePin,
		byte[] authToken,
		byte[] deviceId
	) {
		super(email, password);
		defineFields(
			"profileId", profileId,
			"profilePin", profilePin,
			"authToken", authToken,
			"deviceId", deviceId
		);
	}
	
	public OneplayCredentials(
		String email,
		String password,
		String profileId,
		String profilePin,
		String authToken,
		String deviceId
	) {
		this(
			CredentialsUtils.bytes(email),
			CredentialsUtils.bytes(password),
			CredentialsUtils.bytes(profileId),
			CredentialsUtils.bytes(profilePin),
			CredentialsUtils.bytes(authToken),
			CredentialsUtils.bytes(deviceId)
		);
	}
	
	public String profileId() {
		return isInitialized() ? CredentialsUtils.string(get("profileId")) : null;
	}
	
	public String profilePin() {
		return isInitialized() ? CredentialsUtils.string(get("profilePin")) : null;
	}
	
	public String authToken() {
		return isInitialized() ? CredentialsUtils.string(get("authToken")) : null;
	}
	
	public String deviceId() {
		return isInitialized() ? CredentialsUtils.string(get("deviceId")) : null;
	}
}
