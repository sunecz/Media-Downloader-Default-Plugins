package sune.app.mediadown.media_engine.novavoyo;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import sune.app.mediadown.authentication.CredentialsManager;
import sune.app.mediadown.media_engine.novavoyo.Connection.Response;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;
import sune.app.mediadown.util.Utils;

public final class Authenticator {
	
	private Authenticator() {
	}
	
	private static final String doSelectProfile(Connection connection, Profile profile)
			throws Exception {
		JSONCollection payload = JSONCollection.ofObject(
			"profileId", JSONObject.ofString(profile.id())
		);
		
		Response response = connection.request("user.profile.select", payload);
		
		return (
			response.isSuccess()
				? response.data().getString("bearerToken")
				: null
		);
	}
	
	private static final String doLogin(Connection connection, OneplayCredentials credentials)
			throws Exception {
		JSONCollection args = JSONCollection.ofObject(
			"email", JSONObject.ofString(credentials.email()),
			"password", JSONObject.ofString(credentials.password())
		);
		
		Response response = connection.command(
			"user.login.step",
			"LoginWithCredentialsCommand",
			args
		);
		
		return (
			response.isSuccess()
				? response.data().getString("step.bearerToken")
				: null
		);
	}
	
	private static final String currentDeviceId(Connection connection) throws Exception {
		JSONCollection payload = JSONCollection.ofObject(
			"reason", JSONObject.ofString("login")
		);
		
		Response response = connection.request("app.init", payload);
		return response.data().getString("user.currentDevice.id");
	}
	
	private static final AuthenticationData credentialsToAuthData(OneplayCredentials credentials) {
		return new AuthenticationData(
			credentials.profileId(),
			credentials.authToken(),
			credentials.deviceId()
		);
	}
	
	private static final boolean authDataEquals(
		OneplayCredentials credentials,
		AuthenticationData data
	) {
		return credentialsToAuthData(credentials).equals(data);
	}
	
	public static final OneplayCredentials credentials() throws IOException {
		return (OneplayCredentials) CredentialsManager.instance().get(Common.credentialsName());
	}
	
	public static final boolean hasCredentials() {
		try {
			return CredentialsManager.instance().has(Common.credentialsName());
		} catch(IOException ex) {
			return false;
		}
	}
	
	public static final Profile selectProfile(Connection connection, String profileId)
			throws Exception {
		List<Profile> profiles = Profiles.all(connection);
		
		if(profiles.isEmpty()) {
			throw new IllegalStateException("No profiles");
		}
		
		Profile first = profiles.get(0);
		
		if(profileId == null || profileId.isEmpty()) {
			return first;
		}
		
		return (
			profiles.stream()
				.filter((p) -> profileId.equals(p.id()))
				.findFirst()
				.orElse(first)
		);
	}
	
	public static final boolean isAuthenticated(Connection connection) throws Exception {
		JSONCollection payload = JSONCollection.ofObject(
			"screen", JSONObject.ofString("account")
		);
		
		JSONCollection data = connection.request("setting.display", payload).data();
		
		return (
			!"Error".equals(data.getString("status"))
				&& !"4001".equals(data.getString("code"))
		);
	}
	
	public static final AuthenticationData login(Connection connection) throws Exception {
		String authToken, selectedProfileId;
		
		try(OneplayCredentials credentials = credentials()) {
			authToken = credentials.authToken();
			
			if(authToken != null && !authToken.isEmpty()) {
				connection.authenticate(authToken); // Try to use the saved authentication token
				
				if(isAuthenticated(connection)) {
					return credentialsToAuthData(credentials); // Valid saved credentials
				}
				
				connection.authenticate(null); // Reset the authentication token
			}
			
			if((authToken = doLogin(connection, credentials)) == null) {
				throw new IllegalStateException("Failed to log in");
			}
			
			connection.authenticate(authToken);
			selectedProfileId = credentials.profileId();
		}
		
		Profile profile = selectProfile(connection, selectedProfileId);
		authToken = doSelectProfile(connection, profile);
		String profileId = profile.id();
		String deviceId = currentDeviceId(connection);
		
		return new AuthenticationData(profileId, authToken, deviceId);
	}
	
	public static final void rememberAuthenticationData(AuthenticationData data)
			throws IOException {
		OneplayCredentials newCredentials = null;
		
		try {
			try(OneplayCredentials oldCredentials = credentials()) {
				// Do not re-save the credentials if they are the same.
				if(authDataEquals(oldCredentials, data)) {
					return;
				}
				
				newCredentials = new OneplayCredentials(
					oldCredentials.email(),
					oldCredentials.password(),
					data.profileId(),
					data.authToken(),
					data.deviceId()
				);
			}
			
			CredentialsManager.instance().set(Common.credentialsName(), newCredentials);
		} finally {
			if(newCredentials != null) {
				newCredentials.close();
			}
		}
	}
	
	public static final class Profiles {
		
		private Profiles() {
		}
		
		private static final Profile parseProfile(JSONCollection json) {
			return new Profile(json.getString("profile.id"), json.getString("profile.name"));
		}
		
		public static final List<Profile> all(Connection connection) throws Exception {
			Response response = connection.request("user.profiles.display");
			JSONCollection profiles = response.data().getCollection("availableProfiles.profiles");
			
			return (
				Utils.stream(profiles.collectionsIterable())
					.map(Profiles::parseProfile)
					.collect(Collectors.toList())
			);
		}
	}
	
	public static final class AuthenticationData {
		
		private final String profileId;
		private final String authToken;
		private final String deviceId;
		
		public AuthenticationData(String profileId, String authToken, String deviceId) {
			this.profileId = profileId;
			this.authToken = authToken;
			this.deviceId = deviceId;
		}
		
		public String profileId() { return profileId; }
		public String authToken() { return authToken; }
		public String deviceId() { return deviceId; }
		
		@Override
		public int hashCode() {
			return Objects.hash(authToken, deviceId, profileId);
		}
		
		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(obj == null)
				return false;
			if(getClass() != obj.getClass())
				return false;
			AuthenticationData other = (AuthenticationData) obj;
			return Objects.equals(authToken, other.authToken)
				&& Objects.equals(deviceId, other.deviceId)
				&& Objects.equals(profileId, other.profileId);
		}
	}
}
