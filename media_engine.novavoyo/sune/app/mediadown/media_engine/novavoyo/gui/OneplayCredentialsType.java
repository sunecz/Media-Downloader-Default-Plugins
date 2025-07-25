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
import sune.app.mediadown.media_engine.novavoyo.Authenticator;
import sune.app.mediadown.media_engine.novavoyo.Common;
import sune.app.mediadown.media_engine.novavoyo.Oneplay;
import sune.app.mediadown.media_engine.novavoyo.OneplayCredentials;
import sune.app.mediadown.media_engine.novavoyo.Profile;
import sune.app.mediadown.util.FXUtils;
import sune.app.mediadown.util.Regex;

public class OneplayCredentialsType extends CredentialsType<OneplayCredentials> {
	
	private final Translation translation;
	
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
		Label lblProfile = new Label(tr.getSingle("profile"));
		ProfileSelect cmbProfile = new ProfileSelect(translation.getTranslation("profile"));
		cmbProfile.getStyleClass().add("field-profile");
		Label lblProfilePin = new Label(tr.getSingle("profile_pin"));
		PasswordFieldPane txtProfilePin = new PasswordFieldPane();
		txtProfilePin.textControl().setTextFormatter(AllowMax4DigitsFilters.create());
		txtProfilePin.passwordControl().setTextFormatter(AllowMax4DigitsFilters.create());
		txtProfilePin.getStyleClass().add("field-profile-pin");
		grid.getChildren().addAll(
			lblEmail, txtEmail,
			lblPassword, txtPassword,
			lblProfile, cmbProfile,
			lblProfilePin, txtProfilePin
		);
		GridPane.setConstraints(lblEmail, 0, 0);
		GridPane.setConstraints(txtEmail, 1, 0);
		GridPane.setConstraints(lblPassword, 0, 1);
		GridPane.setConstraints(txtPassword, 1, 1);
		GridPane.setConstraints(lblProfile, 0, 2);
		GridPane.setConstraints(cmbProfile, 1, 2);
		GridPane.setConstraints(lblProfilePin, 0, 3);
		GridPane.setConstraints(txtProfilePin, 1, 3);
		GridPane.setHgrow(txtEmail, Priority.ALWAYS);
		GridPane.setHgrow(txtPassword, Priority.ALWAYS);
		GridPane.setHgrow(cmbProfile, Priority.ALWAYS);
		GridPane.setHgrow(txtProfilePin, Priority.ALWAYS);
		return grid;
	}
	
	@Override
	public void load(Pane pane, OneplayCredentials credentials) {
		GridPane grid = (GridPane) pane;
		TextField txtEmail = (TextField) grid.lookup(".field-email");
		PasswordFieldPane txtPassword = (PasswordFieldPane) grid.lookup(".field-password");
		ProfileSelect cmbProfile = (ProfileSelect) grid.lookup(".field-profile");
		PasswordFieldPane txtProfilePin = (PasswordFieldPane) grid.lookup(".field-profile-pin");
		txtEmail.setText(credentials.email());
		txtPassword.setText(credentials.password());
		cmbProfile.value(credentials.profileId());
		txtProfilePin.setText(credentials.profilePin());
	}
	
	@Override
	public OneplayCredentials save(Pane pane) {
		GridPane grid = (GridPane) pane;
		TextField txtEmail = (TextField) grid.lookup(".field-email");
		PasswordFieldPane txtPassword = (PasswordFieldPane) grid.lookup(".field-password");
		ProfileSelect cmbProfile = (ProfileSelect) grid.lookup(".field-profile");
		PasswordFieldPane txtProfilePin = (PasswordFieldPane) grid.lookup(".field-profile-pin");
		String email = txtEmail.getText();
		String password = txtPassword.getText();
		String profile = cmbProfile.value();
		String profilePin = txtProfilePin.getText();
		String authToken = ""; // Empty due to possible login and profile data change
		String deviceId = ""; // Empty due to possible login and profile data change
		return new OneplayCredentials(email, password, profile, profilePin, authToken, deviceId);
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
			return Oneplay.instance().profiles();
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
					boolean hasCredentials = Authenticator.hasCredentials();
					
					if(hasCredentials) {
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
					Common.error(ex);
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
