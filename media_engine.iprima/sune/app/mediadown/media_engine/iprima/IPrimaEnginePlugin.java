package sune.app.mediadown.media_engine.iprima;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.authentication.CredentialsMigrator;
import sune.app.mediadown.authentication.CredentialsUtils;
import sune.app.mediadown.authentication.EmailCredentials;
import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.entity.MediaEngines;
import sune.app.mediadown.gui.GUI.CredentialsRegistry;
import sune.app.mediadown.gui.GUI.CredentialsRegistry.CredentialsEntry;
import sune.app.mediadown.gui.GUI.CredentialsRegistry.CredentialsType;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media_engine.iprima.PrimaAuthenticator.Profiles.Profile;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.util.FXUtils;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Password;

@Plugin(name          = "media_engine.iprima",
	    title         = "plugin.media_engine.iprima.title",
	    version       = "00.02.09-0013",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/iprima/",
	    updatable     = true,
	    url           = "https://iprima.cz",
	    icon          = "resources/media_engine/iprima/icon/iprima.png")
public final class IPrimaEnginePlugin extends PluginBase {
	
	private static final String NAME = "iprima";
	
	private String translatedTitle;
	private PluginConfiguration.Builder configuration;
	private boolean credentialsMigrated;
	
	private final void initConfiguration() throws IOException {
		PluginConfiguration.Builder builder
			= new PluginConfiguration.Builder(getContext().getPlugin().instance().name());
		String group = builder.name() + ".general";
		
		if(!CredentialsMigrator.isMigrated(credentialsName())) {
			builder.addProperty(ConfigurationProperty.ofString("authData_email")
				.inGroup(group));
			builder.addProperty(ConfigurationProperty.ofType("authData_password", Password.class)
				.inGroup(group)
				.withTransformer(Password::value, Password::new)
				.withDefaultValue(""));
			builder.addProperty(ConfigurationProperty.ofString("profile")
				.inGroup(group)
				.withDefaultValue("auto"));
		}
		
		configuration = builder;
	}
	
	private final String credentialsName() {
		return "plugin/" + getContext().getPlugin().instance().name().replace('.', '/');
	}
	
	private final void initCredentials() throws IOException {
		String name = credentialsName();
		
		Translation translation = translation().getTranslation("credentials");
		CredentialsRegistry.registerType(new IPrimaCredentialsType(translation));
		
		CredentialsRegistry.registerEntry(
			CredentialsEntry.of(name, translatedTitle, this::getIcon)
		);
		
		if(CredentialsMigrator.isMigrated(name)) {
			return; // Nothing to do
		}
		
		CredentialsMigrator
			.ofConfiguration(configuration, "authData_email", "authData_password", "profile")
			.asCredentials(IPrimaCredentials.class, String.class, String.class, String.class)
			.migrate(name);
		
		credentialsMigrated = true;
	}
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, IPrimaEngine.class);
		initConfiguration();
		IPrimaHelper.setPlugin(PluginLoaderContext.getContext().getInstance());
	}
	
	@Override
	public void beforeBuildConfiguration() throws Exception {
		initCredentials();
	}
	
	@Override
	public void afterBuildConfiguration() throws Exception {
		if(credentialsMigrated) {
			// Save the configuration so that the migrated fields are removed
			PluginConfiguration configuration = getContext().getConfiguration();
			
			if(configuration != null) {
				NIO.save(configuration.path(), configuration.data().toString());
			}
		}
	}
	
	@Override
	public void dispose() throws Exception {
		// Do nothing
	}
	
	@Override
	public PluginConfiguration.Builder configuration() {
		return configuration;
	}
	
	@Override
	public String getTitle() {
		return translatedTitle;
	}
	
	protected static final class IPrimaCredentials extends EmailCredentials {
		
		public IPrimaCredentials() {
			super();
			defineFields(
				"profile", "auto"
			);
		}
		
		public IPrimaCredentials(byte[] email, byte[] password, byte[] profile) {
			super(email, password);
			defineFields(
				"profile", profile
			);
		}
		
		public IPrimaCredentials(String email, String password, String profile) {
			this(
				CredentialsUtils.bytes(email),
				CredentialsUtils.bytes(password),
				CredentialsUtils.bytes(profile)
			);
		}
		
		public String profile() {
			return isInitialized() ? CredentialsUtils.string(get("profile")) : null;
		}
	}
	
	protected static class IPrimaCredentialsType extends CredentialsType<IPrimaCredentials> {
		
		private final Translation translation;
		
		protected IPrimaCredentialsType(Translation translation) {
			super(IPrimaCredentials.class);
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
			PasswordField txtPassword = new PasswordField();
			txtPassword.getStyleClass().add("field-password");
			Label lblProfile = new Label(tr.getSingle("profile"));
			ProfileSelect cmbProfile = new ProfileSelect(translation.getTranslation("profile"));
			cmbProfile.getStyleClass().add("field-profile");
			grid.getChildren().addAll(
				lblEmail, txtEmail,
				lblPassword, txtPassword,
				lblProfile, cmbProfile
			);
			GridPane.setConstraints(lblEmail, 0, 0);
			GridPane.setConstraints(txtEmail, 1, 0);
			GridPane.setConstraints(lblPassword, 0, 1);
			GridPane.setConstraints(txtPassword, 1, 1);
			GridPane.setConstraints(lblProfile, 0, 2);
			GridPane.setConstraints(cmbProfile, 1, 2);
			GridPane.setHgrow(txtEmail, Priority.ALWAYS);
			GridPane.setHgrow(txtPassword, Priority.ALWAYS);
			GridPane.setHgrow(cmbProfile, Priority.ALWAYS);
			return grid;
		}
		
		@Override
		public void load(Pane pane, IPrimaCredentials credentials) {
			GridPane grid = (GridPane) pane;
			TextField txtEmail = (TextField) grid.lookup(".field-email");
			PasswordField txtPassword = (PasswordField) grid.lookup(".field-password");
			ProfileSelect cmbProfile = (ProfileSelect) grid.lookup(".field-profile");
			txtEmail.setText(credentials.email());
			txtPassword.setText(credentials.password());
			cmbProfile.value(credentials.profile());
		}
		
		@Override
		public IPrimaCredentials save(Pane pane) {
			GridPane grid = (GridPane) pane;
			TextField txtEmail = (TextField) grid.lookup(".field-email");
			PasswordField txtPassword = (PasswordField) grid.lookup(".field-password");
			ProfileSelect cmbProfile = (ProfileSelect) grid.lookup(".field-profile");
			String email = txtEmail.getText();
			String password = txtPassword.getText();
			String profile = cmbProfile.value();
			return new IPrimaCredentials(email, password, profile);
		}
		
		private final class ProfileSelect extends VBox {
			
			private volatile boolean itemsLoaded = false;
			
			private final Translation translation;
			private final ComboBox<Profile> control;
			private final Label lblProgress;
			private String loadedValue;
			
			public ProfileSelect(Translation translation) {
				super(5.0);
				this.translation = translation;
				control = new ComboBox<>();
				control.setCellFactory((p) -> new ProfileCell());
				control.setButtonCell(new ProfileCell());
				control.setMaxWidth(Double.MAX_VALUE);
				lblProgress = new Label();
				getChildren().addAll(control, lblProgress);
				FXUtils.onWindowShow(control, this::loadItems);
			}
			
			private final List<Profile> profiles() throws Exception {
				return PrimaAuthenticator.profiles();
			}
			
			private final Profile automaticProfile() {
				return new Profile("auto", translation.getSingle("profile_auto"));
			}
			
			private final void enableSelect(boolean enable) {
				FXUtils.thread(() -> control.setDisable(!enable));
			}
			
			private final void progressText(String text) {
				FXUtils.thread(() -> {
					if(text == null) {
						getChildren().remove(lblProgress);
					} else {
						lblProgress.setText(text);
					}
				});
			}
			
			private final void loadItems() {
				Threads.execute(() -> {
					enableSelect(false);
					progressText(translation.getSingle("progress.log_in"));
					
					try {
						PrimaAuthenticator.SessionData session = PrimaAuthenticator.sessionData();
						
						if(session != null) {
							progressText(translation.getSingle("progress.profiles"));
							
							List<Profile> profiles = profiles();
							profiles.add(0, automaticProfile());
							
							Profile selected = profiles.stream()
								.filter((d) -> d.id().equals(loadedValue))
								.findFirst().orElse(null);
							
							FXUtils.thread(() -> {
								control.getItems().setAll(profiles);
								
								if(selected != null) {
									control.getSelectionModel().select(selected);
								}
							});
							
							enableSelect(true);
						}
					} catch(Exception ex) {
						MediaDownloader.error(ex);
					} finally {
						progressText(null);
						itemsLoaded = true;
					}
				});
			}
			
			private final String selectedProfileId() {
				Profile profile = control.getSelectionModel().getSelectedItem();
				
				if(profile == null) {
					return "auto";
				}
				
				return profile.id();
			}
			
			public void value(String profileId) {
				loadedValue = profileId;
			}
			
			public String value() {
				return itemsLoaded ? selectedProfileId() : loadedValue;
			}
		}
		
		private static final class ProfileCell extends ListCell<Profile> {
			
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