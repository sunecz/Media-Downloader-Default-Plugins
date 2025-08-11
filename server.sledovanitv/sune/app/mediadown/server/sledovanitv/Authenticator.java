package sune.app.mediadown.server.sledovanitv;

import java.io.IOException;

import sune.app.mediadown.authentication.CredentialsManager;

public final class Authenticator {
	
	private Authenticator() {
	}
	
	private static final SledovaniTVCredentials credentials() throws IOException {
		return (SledovaniTVCredentials) CredentialsManager.instance().get(Common.credentialsName());
	}
	
	private static final boolean isValidString(String string) {
		return string != null && !string.isEmpty();
	}
	
	public static final void saveSession(API.Session session) throws IOException {
		SledovaniTVCredentials newCredentials = null;
		
		try {
			try(SledovaniTVCredentials oldCredentials = credentials()) {
				newCredentials = new SledovaniTVCredentials(
					oldCredentials.username(),
					oldCredentials.password(),
					session.deviceId(),
					session.profileId(),
					session.sessionId()
				);
			}
			
			CredentialsManager.instance().set(Common.credentialsName(), newCredentials);
		} finally {
			if(newCredentials != null) {
				newCredentials.close();
			}
		}
	}
	
	public static final API.Session authenticate(API api, boolean forceReauth) throws Exception {
		String username = null;
		String password = null;
		
		try(SledovaniTVCredentials credentials = credentials()) {
			if(!forceReauth) {
				String deviceId = credentials.deviceId();
				String profileId = credentials.profileId();
				String sessionId = credentials.sessionId();
				
				if(isValidString(deviceId)
						&& isValidString(profileId)
						&& isValidString(sessionId)) {
					// Reuse session, if present. Whether the session is valid or not will
					// be revealed when an authenticated action (e.g. getting a media source)
					// is required. In that case the session should be discarded and a new
					// login process should be initiated.
					return new API.Session(deviceId, profileId, sessionId);
				}
			}
			
			username = credentials.username();
			password = credentials.password();
		}
		
		if(!isValidString(username) || !isValidString(password)) {
			throw new IllegalArgumentException("Invalid login credentials");
		}
		
		API.DevicePairing pairing = api.createPairing(username, password);
		API.Session session = api.deviceLogin(pairing.deviceId(), pairing.pairingPassword());
		return session;
	}
}
