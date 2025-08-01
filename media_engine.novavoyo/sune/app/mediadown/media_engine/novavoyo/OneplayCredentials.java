package sune.app.mediadown.media_engine.novavoyo;

import sune.app.mediadown.authentication.CredentialsUtils;
import sune.app.mediadown.authentication.EmailCredentials;

public final class OneplayCredentials extends EmailCredentials {
	
	public OneplayCredentials() {
		defineFields(
			"accountId", CredentialsUtils.bytes("auto"),
			"profileId", CredentialsUtils.bytes("auto"),
			"profilePin", CredentialsUtils.bytes(""),
			"authToken", CredentialsUtils.bytes(""),
			"deviceId", CredentialsUtils.bytes(""),
			"accounts", CredentialsUtils.bytes("")
		);
	}
	
	public OneplayCredentials(
		byte[] email,
		byte[] password,
		byte[] accountId,
		byte[] profileId,
		byte[] profilePin,
		byte[] authToken,
		byte[] deviceId,
		byte[] accounts
	) {
		super(email, password);
		defineFields(
			"accountId", accountId,
			"profileId", profileId,
			"profilePin", profilePin,
			"authToken", authToken,
			"deviceId", deviceId,
			"accounts", accounts
		);
	}
	
	public OneplayCredentials(
		String email,
		String password,
		String accountId,
		String profileId,
		String profilePin,
		String authToken,
		String deviceId,
		String accounts
	) {
		this(
			CredentialsUtils.bytes(email),
			CredentialsUtils.bytes(password),
			CredentialsUtils.bytes(accountId),
			CredentialsUtils.bytes(profileId),
			CredentialsUtils.bytes(profilePin),
			CredentialsUtils.bytes(authToken),
			CredentialsUtils.bytes(deviceId),
			CredentialsUtils.bytes(accounts)
		);
	}
	
	public String accountId() {
		return isInitialized() ? CredentialsUtils.string(get("accountId")) : null;
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
	
	public String accounts() {
		return isInitialized() ? CredentialsUtils.string(get("accounts")) : null;
	}
}
