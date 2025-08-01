package sune.app.mediadown.media_engine.novavoyo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import sune.app.mediadown.authentication.CredentialsManager;
import sune.app.mediadown.media_engine.novavoyo.Account.Provider;
import sune.app.mediadown.media_engine.novavoyo.Common.MessageException;
import sune.app.mediadown.media_engine.novavoyo.Common.TranslatableException;
import sune.app.mediadown.media_engine.novavoyo.Connection.Response;
import sune.app.mediadown.media_engine.novavoyo.util.Logging;
import sune.app.mediadown.util.JSON;
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
		
		Logging.logDebug("[Auth] Selecting profile: %s", profile.id());
		Response response = connection.request("user.profile.select", payload);
		String authToken;
		
		if(!response.isSuccess()) {
			// Check whether it is something else than the PIN lock error, and if so, exit early.
			if(!"4080".equals(response.data().getString("code"))) {
				Logging.logDebug("[Auth] Erroneous response: %s", response.data());
				
				throw new MessageException(String.format(
					"Failed to log in (select profile). Reason: %s",
					response.data().getString("message", "Unknown reason")
				));
			}
			
			Logging.logDebug("[Auth] Profile PIN requested, continuing...");
			
			String profilePin;
			try(OneplayCredentials credentials = credentials()) {
				profilePin = credentials.profilePin();
			}
			
			if(profilePin == null || profilePin.length() != 4) {
				Logging.logDebug("[Auth] Invalid profile PIN, exiting.");
				throw new TranslatableException("error.invalid_profile_pin");
			}
			
			Logging.logDebug("[Auth] Authorizing using the profile PIN...");
			
			JSONCollection authorization = JSONCollection.ofObject(
				"schema", JSONObject.ofString("PinRequestAuthorization"),
				"pin", JSONObject.ofString(profilePin),
				"type", JSONObject.ofString("profile")
			);
			
			response = connection.request("user.profile.select", new Connection.Request(
				payload, null, null, JSONCollection.ofArray(authorization)
			));
		}
		
		if(!response.isSuccess()
				|| (authToken = response.data().getString("bearerToken")) == null) {
			Logging.logDebug("[Auth] Erroneous response: %s", response.data());
			
			throw new MessageException(String.format(
				"Failed to log in (select profile). Reason: %s",
				response.data().getString("message", "Unknown reason")
			));
		}
		
		Logging.logDebug("[Auth] Profile access token acquired.");
		return authToken;
	}
	
	private static final SelectedAccount doSelectAccount(
		Connection connection,
		Account account,
		String authCode
	) throws Exception {
		String accountId = account.id();
		
		if(accountId == null) {
			Logging.logDebug("[Auth] No suitable account found, exiting.");
			
			throw new MessageException(
				"Failed to log in (account step). Cannot find any suitable account."
			);
		} else {
			Logging.logDebug("[Auth] Account selected: %s", accountId);
		}
		
		JSONCollection args = JSONCollection.ofObject(
			"accountId", JSONObject.ofString(accountId),
			"authCode", JSONObject.ofString(authCode)
		);
		
		Response response = connection.command(
			"user.login.step",
			"LoginWithAccountCommand",
			args
		);
		
		String authToken;
		
		if(!response.isSuccess()
				|| (authToken = response.data().getString("step.bearerToken")) == null) {
			Logging.logDebug("[Auth] Erroneous response: %s", response.data());
			
			throw new MessageException(String.format(
				"Failed to log in (account step). Reason: %s",
				response.data().getString("message", "Unknown reason")
			));
		}
		
		return new SelectedAccount(accountId, authToken);
	}
	
	private static final LoginResult doLogin(Connection connection, OneplayCredentials credentials)
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
		
		JSONCollection data = response.data();
		String schema = data.getString("step.schema");
		LoginResult result;
		
		if("ShowAccountChooserStep".equals(schema)) {
			JSONCollection rawAccounts = data.getCollection("step.accounts");
			Logging.logDebug("[Auth] Available accounts: %s", rawAccounts);
			
			List<Account> accounts = Accounts.setAccounts(rawAccounts);
			Account account = selectAccount(accounts, credentials.accountId());
			String authCode = data.getString("step.authToken");
			SelectedAccount selected = doSelectAccount(connection, account, authCode);
			result = new LoginResult(selected, accounts);
		} else {
			String authToken;
			
			if(!response.isSuccess()
					|| (authToken = response.data().getString("step.bearerToken")) == null) {
				Logging.logDebug("[Auth] Erroneous response: %s", response.data());
				
				throw new MessageException(String.format(
					"Failed to log in. Reason: %s",
					response.data().getString("message", "Unknown reason")
				));
			}
			
			Accounts.setAccounts((JSONCollection) null);
			result = new LoginResult(new SelectedAccount("", authToken), List.of());
		}
		
		return result;
	}
	
	private static final String currentDeviceId(Connection connection) throws Exception {
		JSONCollection payload = JSONCollection.ofObject(
			"reason", JSONObject.ofString("login")
		);
		
		Response response = connection.request("app.init", payload);
		String deviceId;
		
		if(!response.isSuccess()
				|| (deviceId = response.data().getString("user.currentDevice.id")) == null) {
			Logging.logDebug("[Auth] Erroneous response: %s", response.data());
			
			throw new MessageException(String.format(
				"Failed to obtain device ID. Reason: %s",
				response.data().getString("message", "Unknown reason")
			));
		}
		
		return deviceId;
	}
	
	private static final AuthenticationData credentialsToAuthData(OneplayCredentials credentials) {
		return new AuthenticationData(
			credentials.accountId(),
			credentials.profileId(),
			// Assume that the credentials always have the full authentication token
			new AuthenticationToken(AuthenticationToken.Type.FULL, credentials.authToken()),
			credentials.deviceId(),
			Accounts.parseCredentialsAccounts(credentials.accounts())
		);
	}
	
	private static final boolean authDataEquals(
		OneplayCredentials credentials,
		AuthenticationData data
	) {
		return credentialsToAuthData(credentials).equals(data);
	}
	
	private static final Account selectAccount(List<Account> accounts, String accountId)
			throws Exception {
		if(accounts.isEmpty()) {
			throw new IllegalStateException("No accounts");
		}
		
		Account firstActive = (
			accounts.stream()
				.filter(Account::isActive)
				.findFirst()
				.orElse(null)
		);
		
		if(firstActive == null) {
			throw new IllegalStateException("No active account");
		}
		
		if(accountId == null || accountId.isEmpty()) {
			return firstActive;
		}
		
		return (
			accounts.stream()
				.filter((a) -> a.isActive() && accountId.equals(a.id()))
				.findFirst()
				.orElse(firstActive)
		);
	}
	
	private static final Profile selectProfile(List<Profile> profiles, String profileId)
			throws Exception {
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
	
	public static final OneplayCredentials credentials() throws IOException {
		return (OneplayCredentials) CredentialsManager.instance().get(Common.credentialsName());
	}
	
	public static final boolean hasCredentials() {
		try(OneplayCredentials credentials = credentials()) {
			return (
				Utils.OfString.nonEmpty(credentials.email())
					&& Utils.OfString.nonEmpty(credentials.password())
			);
		} catch(IOException ex) {
			return false;
		}
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
	
	public static final AuthenticationData login(Connection connection, boolean doProfileSelect)
			throws Exception {
		String authTokenValue, selectedAccountId, selectedProfileId;
		List<Account> accounts;
		AuthenticationToken.Type authTokenType = AuthenticationToken.Type.NO_PROFILE;
		AuthenticationToken authToken = null;
		
		try(OneplayCredentials credentials = credentials()) {
			authTokenValue = credentials.authToken();
			selectedAccountId = credentials.accountId();
			accounts = List.of();
			
			if(authTokenValue != null && !authTokenValue.isEmpty()) {
				authToken = new AuthenticationToken(authTokenType, authTokenValue);
				connection.authenticate(authToken); // Try to use the saved authentication token
				Logging.logDebug("[Auth] Trying the saved access token...");
				
				if(isAuthenticated(connection)) {
					Logging.logDebug("[Auth] The saved access token is valid.");
					Accounts.setAccounts(credentials.accounts());
					return credentialsToAuthData(credentials); // Valid saved credentials
				}
				
				Logging.logDebug("[Auth] Access token is invalid, continuing...");
				connection.authenticate(null); // Reset the authentication token
			} else {
				Logging.logDebug("[Auth] No access token saved, continuing...");
			}
			
			if(!isAuthenticated(connection)) {
				Logging.logDebug("[Auth] Connection not authenticated, doing the login process...");
				LoginResult result = doLogin(connection, credentials);
				SelectedAccount selectedAccount = result.selectedAccount();
				accounts = result.accounts();
				selectedAccountId = selectedAccount.accountId();
				authTokenValue = selectedAccount.authToken();
				authToken = new AuthenticationToken(authTokenType, authTokenValue);
				connection.authenticate(authToken);
			} else {
				Logging.logDebug("[Auth] Connection already authenticated, skipping the login process...");
			}
			
			selectedProfileId = credentials.profileId();
		}
		
		List<Profile> profiles = Profiles.all(connection);
		Profile profile = selectProfile(profiles, selectedProfileId);
		
		String accountId = selectedAccountId;
		String deviceId = ""; // Must be non-null
		String profileId = profile.id();
		
		// Allow skipping the profile selection process. This is needed in the case when
		// a profile list is being queried but the automatically selected profile has a PIN lock.
		// If this was not allowed, the user would be unable to select another profile without
		// knowing the actual PIN.
		if(doProfileSelect) {
			Logging.logDebug("[Auth] Profile selection requested, doing the profile selection process...");
			authTokenValue = doSelectProfile(connection, profile);
			deviceId = currentDeviceId(connection);
			authTokenType = AuthenticationToken.Type.FULL;
			authToken = new AuthenticationToken(authTokenType, authTokenValue);
		} else {
			Logging.logDebug("[Auth] Profile selection not requested, skipping...");
		}
		
		Logging.logDebug("[Auth] Authentication process is done (type=%s).", authToken.type());
		return new AuthenticationData(accountId, profileId, authToken, deviceId, accounts);
	}
	
	public static final void rememberAuthenticationData(AuthenticationData data)
			throws IOException {
		// Remember only the fully authenticated token
		if(data.authToken().type() != AuthenticationToken.Type.FULL) {
			return; // Do not save
		}
		
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
					data.accountId(),
					data.profileId(),
					oldCredentials.profilePin(),
					data.authToken().value(),
					data.deviceId(),
					Accounts.serializeCredentialsAccounts(data.accounts())
				);
			}
			
			CredentialsManager.instance().set(Common.credentialsName(), newCredentials);
		} finally {
			if(newCredentials != null) {
				newCredentials.close();
			}
		}
	}
	
	private static final class SelectedAccount {
		
		private final String accountId;
		private final String authToken;
		
		public SelectedAccount(String accountId, String authToken) {
			this.accountId = accountId;
			this.authToken = authToken;
		}
		
		public String accountId() { return accountId; }
		public String authToken() { return authToken; }
	}
	
	private static final class LoginResult {
		
		private final SelectedAccount selectedAccount;
		private final List<Account> accounts;
		
		public LoginResult(SelectedAccount selectedAccount, List<Account> accounts) {
			this.selectedAccount = selectedAccount;
			this.accounts = accounts;
		}
		
		public SelectedAccount selectedAccount() { return selectedAccount; }
		public List<Account> accounts() { return accounts; }
	}
	
	public static final class Accounts {
		
		private static final List<Account> cached = new ArrayList<>();
		
		private Accounts() {
		}
		
		private static final Account parseAccount(JSONCollection json) {
			String id = json.getString("accountId");
			Account.Provider provider = Account.Provider.of(json.getString("accountProvider"));
			String name = json.getString("name");
			boolean isActive = json.getBoolean("isActive");
			return new Account(id, provider, name, isActive);
		}
		
		private static final List<Account> parseCredentialsAccounts(String accounts) {
			List<Account> list = new ArrayList<>();
			
			if(accounts == null || accounts.isEmpty()) {
				return list;
			}
			
			JSONCollection array = JSON.read(accounts);
			
			for(JSONCollection item : array.collectionsIterable()) {
				list.add(new Account(
					item.getString("id"),
					Account.Provider.of(item.getString("provider")),
					item.getString("name"),
					item.getBoolean("isActive")
				));
			}
			
			return list;
		}
		
		private static final String serializeCredentialsAccounts(List<Account> accounts) {
			JSONCollection array = JSONCollection.emptyArray();
			
			for(Account account : accounts) {
				// Do not include synthetic accounts created by us
				if(account.id().isEmpty() || "auto".equals(account.id())) {
					continue;
				}
				
				array.add(account.toJSON());
			}
			
			return array.toString(true);
		}
		
		private static final List<Account> setAccounts(String accounts) {
			if(accounts == null || accounts.isEmpty()) {
				return setAccounts((JSONCollection) null);
			}
			
			return setAccounts(JSON.read(accounts));
		}
		
		private static final List<Account> setAccounts(JSONCollection accounts) {
			cached.clear();
			
			if(accounts == null || accounts.isEmpty()) {
				Account defaultAccount = new Account(
					"", // Don't use null
					Provider.EBOX, // No other accounts are present, thus this is a direct Oneplay account
					"Oneplay", // Default name as used by Oneplay itself
					true // The only available account should be active
				);
				
				cached.add(defaultAccount);
				return cached;
			}
			
			Utils.stream(accounts.collectionsIterator())
				.map(Accounts::parseAccount)
				.forEachOrdered(cached::add);
			
			return cached;
		}
		
		public static final List<Account> all(Connection connection) throws Exception {
			// Since I don't have access to an account with multiple accounts, I can only
			// extrapolate from the minified source code available on the Oneplay website.
			// It seems that the list of accounts is only available during the login process,
			// i.e. there is no separate page for selecting a different account as is for
			// profiles, thus we can only obtain them there. The strategy is to do the login
			// process and during the initial account selection, save the list and then reuse
			// it everywhere else.
			return cached;
		}
	}
	
	public static final class Profiles {
		
		private Profiles() {
		}
		
		private static final Profile parseProfile(JSONCollection json) {
			return new Profile(json.getString("profile.id"), json.getString("profile.name"));
		}
		
		public static final List<Profile> all(Connection connection) throws Exception {
			return Common.handleErrors(() -> {
				Response response = connection.request("user.profiles.display");
				JSONCollection profiles;
				
				if(!response.isSuccess()
						|| (profiles = response.data().getCollection("availableProfiles.profiles")) == null) {
					Logging.logDebug("[Auth] Erroneous response: %s", response.data());
					
					throw new MessageException(String.format(
						"Failed to get profiles. Reason: %s",
						response.data().getString("message", "Unknown reason")
					));
				}
				
				return (
					Utils.stream(profiles.collectionsIterable())
						.map(Profiles::parseProfile)
						.collect(Collectors.toList())
				);
			});
		}
	}
	
	public static final class AuthenticationToken {
		
		public static enum Type { NO_PROFILE, FULL; }
		
		private final Type type;
		private final String value;
		
		public AuthenticationToken(Type type, String value) {
			this.type = Objects.requireNonNull(type);
			this.value = Objects.requireNonNull(value);
		}
		
		public Type type() { return type; }
		public String value() { return value; }
		
		@Override
		public int hashCode() {
			return Objects.hash(type, value);
		}
		
		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(obj == null)
				return false;
			if(getClass() != obj.getClass())
				return false;
			AuthenticationToken other = (AuthenticationToken) obj;
			return type == other.type && Objects.equals(value, other.value);
		}
	}
	
	public static final class AuthenticationData {
		
		private final String accountId;
		private final String profileId;
		private final AuthenticationToken authToken;
		private final String deviceId;
		private final List<Account> accounts;
		
		public AuthenticationData(
			String accountId,
			String profileId,
			AuthenticationToken authToken,
			String deviceId,
			List<Account> accounts
		) {
			this.accountId = accountId;
			this.profileId = profileId;
			this.authToken = authToken;
			this.deviceId = deviceId;
			this.accounts = accounts;
		}
		
		public String accountId() { return accountId; }
		public String profileId() { return profileId; }
		public AuthenticationToken authToken() { return authToken; }
		public String deviceId() { return deviceId; }
		public List<Account> accounts() { return accounts; }
		
		// Note: Don't use `accounts` in `hashCode` and `equals` methods.
		//       The data are still valid since the account ID must be from the list of accounts.
		//       Different lists should not cause the data to be different themselves.
		
		@Override
		public int hashCode() {
			return Objects.hash(accountId, authToken, deviceId, profileId);
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
			return Objects.equals(accountId, other.accountId)
				&& Objects.equals(authToken, other.authToken)
				&& Objects.equals(deviceId, other.deviceId)
				&& Objects.equals(profileId, other.profileId);
		}
	}
}
