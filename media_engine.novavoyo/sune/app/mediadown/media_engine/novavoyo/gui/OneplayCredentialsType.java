package sune.app.mediadown.media_engine.novavoyo.gui;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextFormatter.Change;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.gui.GUI.CredentialsRegistry.CredentialsType;
import sune.app.mediadown.gui.control.PasswordFieldPane;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media_engine.novavoyo.Account;
import sune.app.mediadown.media_engine.novavoyo.Account.Provider;
import sune.app.mediadown.media_engine.novavoyo.Authenticator;
import sune.app.mediadown.media_engine.novavoyo.Common;
import sune.app.mediadown.media_engine.novavoyo.Oneplay;
import sune.app.mediadown.media_engine.novavoyo.OneplayCredentials;
import sune.app.mediadown.media_engine.novavoyo.Profile;
import sune.app.mediadown.util.FXUtils;
import sune.app.mediadown.util.Regex;

public class OneplayCredentialsType extends CredentialsType<OneplayCredentials> {
	
	private final Translation translation;
	private String accounts; // Need to hold the original value for saving purposes
	
	public OneplayCredentialsType(Translation translation) {
		super(OneplayCredentials.class);
		this.translation = Objects.requireNonNull(translation);
	}
	
	@Override
	public Pane create() {
		GridPane grid = new GridPane();
		grid.setHgap(5.0);
		grid.setVgap(5.0);
		Translation tr = translation.getTranslation("label");
		Label lblEmail = new Label(tr.getSingle("email"));
		TextField txtEmail = new TextField();
		txtEmail.getStyleClass().add("field-email");
		Label lblPassword = new Label(tr.getSingle("password"));
		PasswordFieldPane txtPassword = new PasswordFieldPane();
		txtPassword.getStyleClass().add("field-password");
		Label lblAccount = new Label(tr.getSingle("account"));
		AccountSelect cmbAccount = new AccountSelect(translation.getTranslation("account"));
		cmbAccount.getStyleClass().add("field-account");
		Label lblProfile = new Label(tr.getSingle("profile"));
		ProfileSelect cmbProfile = new ProfileSelect(translation.getTranslation("profile"));
		cmbProfile.getStyleClass().add("field-profile");
		Label lblProfilePin = new Label(tr.getSingle("profile_pin"));
		PasswordFieldPane txtProfilePin = new PasswordFieldPane();
		txtProfilePin.textControl().setTextFormatter(AllowMax4DigitsFilters.create());
		txtProfilePin.passwordControl().setTextFormatter(AllowMax4DigitsFilters.create());
		txtProfilePin.getStyleClass().add("field-profile-pin");
		
		// Each account has its separate profiles, thus on every account change we have to
		// clear the profile select.
		cmbAccount.control.getSelectionModel().selectedIndexProperty().addListener((o, ov, nv) -> {
			Oneplay.instance().deauthenticate();
			cmbProfile.reload();
		});
		
		grid.getChildren().addAll(
			lblEmail, txtEmail,
			lblPassword, txtPassword,
			lblAccount, cmbAccount,
			lblProfile, cmbProfile,
			lblProfilePin, txtProfilePin
		);
		GridPane.setConstraints(lblEmail, 0, 0);
		GridPane.setConstraints(txtEmail, 1, 0);
		GridPane.setConstraints(lblPassword, 0, 1);
		GridPane.setConstraints(txtPassword, 1, 1);
		GridPane.setConstraints(lblAccount, 0, 2);
		GridPane.setConstraints(cmbAccount, 1, 2);
		GridPane.setConstraints(lblProfile, 0, 3);
		GridPane.setConstraints(cmbProfile, 1, 3);
		GridPane.setConstraints(lblProfilePin, 0, 4);
		GridPane.setConstraints(txtProfilePin, 1, 4);
		GridPane.setHgrow(txtEmail, Priority.ALWAYS);
		GridPane.setHgrow(txtPassword, Priority.ALWAYS);
		GridPane.setHgrow(cmbAccount, Priority.ALWAYS);
		GridPane.setHgrow(cmbProfile, Priority.ALWAYS);
		GridPane.setHgrow(txtProfilePin, Priority.ALWAYS);
		return grid;
	}
	
	@Override
	public void load(Pane pane, OneplayCredentials credentials) {
		GridPane grid = (GridPane) pane;
		TextField txtEmail = (TextField) grid.lookup(".field-email");
		PasswordFieldPane txtPassword = (PasswordFieldPane) grid.lookup(".field-password");
		AccountSelect cmbAccount = (AccountSelect) grid.lookup(".field-account");
		ProfileSelect cmbProfile = (ProfileSelect) grid.lookup(".field-profile");
		PasswordFieldPane txtProfilePin = (PasswordFieldPane) grid.lookup(".field-profile-pin");
		txtEmail.setText(credentials.email());
		txtPassword.setText(credentials.password());
		cmbAccount.value(credentials.accountId());
		cmbProfile.value(credentials.profileId());
		txtProfilePin.setText(credentials.profilePin());
		accounts = credentials.accounts();
	}
	
	@Override
	public OneplayCredentials save(Pane pane) {
		GridPane grid = (GridPane) pane;
		TextField txtEmail = (TextField) grid.lookup(".field-email");
		PasswordFieldPane txtPassword = (PasswordFieldPane) grid.lookup(".field-password");
		AccountSelect cmbAccount = (AccountSelect) grid.lookup(".field-account");
		ProfileSelect cmbProfile = (ProfileSelect) grid.lookup(".field-profile");
		PasswordFieldPane txtProfilePin = (PasswordFieldPane) grid.lookup(".field-profile-pin");
		String email = txtEmail.getText();
		String password = txtPassword.getText();
		String account = cmbAccount.value();
		String profile = cmbProfile.value();
		String profilePin = txtProfilePin.getText();
		String authToken = ""; // Empty due to possible login and profile data change
		String deviceId = ""; // Empty due to possible login and profile data change
		String rawAccounts = accounts;
		accounts = null; // No longer reference the accounts in memory
		return new OneplayCredentials(
			email, password, account, profile, profilePin, authToken, deviceId, rawAccounts
		);
	}
	
	private static final class AllowMax4DigitsFilters
			implements UnaryOperator<TextFormatter.Change> {
		
		private static final Regex REGEX = Regex.of("\\d{0,4}");
		
		public static final TextFormatter<String> create() {
			return new TextFormatter<>(new AllowMax4DigitsFilters());
		}
		
		@Override
		public Change apply(Change change) {
			return REGEX.matches(change.getControlNewText()) ? change : null;
		}
	}
	
	private static abstract class GenericSelect<T> extends VBox {
		
		protected volatile boolean itemsLoaded = false;
		
		protected final Translation translation;
		protected final ComboBox<T> control;
		protected final Label lblProgress;
		protected String loadedValue;
		
		protected GenericSelect(Translation translation) {
			super(5.0);
			this.translation = translation;
			control = new ComboBox<>();
			control.setCellFactory((p) -> createCell());
			control.setButtonCell(createCell());
			control.setMaxWidth(Double.MAX_VALUE);
			lblProgress = new Label();
			getChildren().addAll(control, lblProgress);
			FXUtils.onWindowShow(control, this::loadItems);
		}
		
		private final void loadItems() {
			Threads.execute(() -> {
				itemsLoaded = false;
				enableSelect(false);
				progressText(translation.getSingle("progress.log_in"));
				
				try {
					boolean hasCredentials = Authenticator.hasCredentials();
					
					if(hasCredentials) {
						boolean enable = doLoadItems();
						enableSelect(enable);
					}
				} catch(Exception ex) {
					Common.error(ex);
				} finally {
					progressText(null);
					itemsLoaded = true;
				}
			});
		}
		
		protected final void enableSelect(boolean enable) {
			FXUtils.thread(() -> control.setDisable(!enable));
		}
		
		protected final void progressText(String text) {
			FXUtils.thread(() -> {
				if(text == null) {
					getChildren().remove(lblProgress);
				} else {
					lblProgress.setText(text);
				}
			});
		}
		
		protected final void setItems(List<T> items, T selected) {
			FXUtils.thread(() -> {
				control.getItems().setAll(items);
				
				if(selected != null) {
					control.getSelectionModel().select(selected);
				}
			});
		}
		
		protected abstract ListCell<T> createCell();
		protected abstract boolean doLoadItems() throws Exception;
		protected abstract String selectedValue();
		
		public void reload() {
			FXUtils.thread(this::loadItems);
		}
		
		public void value(String profileId) {
			loadedValue = profileId;
		}
		
		public String value() {
			return itemsLoaded ? selectedValue() : loadedValue;
		}
	}
	
	private static final class AccountSelect extends GenericSelect<Account> {
		
		public AccountSelect(Translation translation) {
			super(translation);
		}
		
		private final List<Account> accounts() throws Exception {
			return Oneplay.instance().accounts();
		}
		
		private final Account automaticAccount() {
			return new Account("auto", Provider.ANY, translation.getSingle("account_auto"), true);
		}
		
		@Override
		protected final ListCell<Account> createCell() {
			return new Cell();
		}
		
		@Override
		protected final boolean doLoadItems() throws Exception {
			progressText(translation.getSingle("progress.accounts"));
			
			List<Account> accounts = accounts();
			Account automaticAccount = automaticAccount();
			accounts.add(0, automaticAccount);
			
			Account selected = accounts.stream()
				.filter((d) -> d.id().equals(loadedValue))
				.findFirst().orElse(automaticAccount);
			
			setItems(accounts, selected);
			return true;
		}
		
		@Override
		protected final String selectedValue() {
			Account account = control.getSelectionModel().getSelectedItem();
			
			if(account == null) {
				return "auto";
			}
			
			return account.id();
		}
		
		private final class Cell extends ListCell<Account> {
			
			@Override
			protected void updateItem(Account item, boolean empty) {
				super.updateItem(item, empty);
				
				if(!empty) {
					String text = item.name();
					
					if(item.provider() != Account.Provider.ANY || !"auto".equals(item.id())) {
						String state = translation.getSingle(
							"state." + (item.isActive() ? "active" : "inactive")
						);
						
						text = String.format(
							"%s (%s) - %s",
							item.name(), item.provider().name(), state
						);
					}
					
					setText(text);
				} else {
					setText(null);
					setGraphic(null);
				}
			}
		}
	}
	
	private static final class ProfileSelect extends GenericSelect<Profile> {
		
		public ProfileSelect(Translation translation) {
			super(translation);
		}
		
		private final List<Profile> profiles() throws Exception {
			return Oneplay.instance().profiles();
		}
		
		private final Profile automaticProfile() {
			return new Profile("auto", translation.getSingle("profile_auto"));
		}
		
		@Override
		protected final ListCell<Profile> createCell() {
			return new Cell();
		}
		
		@Override
		protected final boolean doLoadItems() throws Exception {
			progressText(translation.getSingle("progress.profiles"));
			
			List<Profile> profiles = profiles();
			Profile automaticProfile = automaticProfile();
			profiles.add(0, automaticProfile);
			
			Profile selected = profiles.stream()
					.filter((d) -> d.id().equals(loadedValue))
					.findFirst().orElse(automaticProfile);
			
			setItems(profiles, selected);
			return true;
		}
		
		@Override
		protected final String selectedValue() {
			Profile profile = control.getSelectionModel().getSelectedItem();
			
			if(profile == null) {
				return "auto";
			}
			
			return profile.id();
		}
		
		private final class Cell extends ListCell<Profile> {
			
			@Override
			protected void updateItem(Profile item, boolean empty) {
				super.updateItem(item, empty);
				
				if(!empty) {
					setText(item.name());
				} else {
					setText(null);
					setGraphic(null);
				}
			}
		}
	}
}
