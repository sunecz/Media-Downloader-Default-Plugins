package sune.app.mediadown.server.sledovanitv.gui;

import java.util.Objects;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import sune.app.mediadown.gui.GUI.CredentialsRegistry.CredentialsType;
import sune.app.mediadown.gui.control.PasswordFieldPane;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.server.sledovanitv.SledovaniTVCredentials;

public class SledovaniTVCredentialsType extends CredentialsType<SledovaniTVCredentials> {
	
	private final Translation translation;
	private String deviceId, profileId, sessionId; // Remember for saving purposes
	
	public SledovaniTVCredentialsType(Translation translation) {
		super(SledovaniTVCredentials.class);
		this.translation = Objects.requireNonNull(translation);
	}
	
	@Override
	public Pane create() {
		GridPane grid = new GridPane();
		grid.setHgap(5.0);
		grid.setVgap(5.0);
		Translation tr = translation.getTranslation("label");
		Label lblUsername = new Label(tr.getSingle("username"));
		TextField txtUsername = new TextField();
		txtUsername.getStyleClass().add("field-username");
		Label lblPassword = new Label(tr.getSingle("password"));
		PasswordFieldPane txtPassword = new PasswordFieldPane();
		txtPassword.getStyleClass().add("field-password");
		grid.getChildren().addAll(
			lblUsername, txtUsername,
			lblPassword, txtPassword
		);
		GridPane.setConstraints(lblUsername, 0, 0);
		GridPane.setConstraints(txtUsername, 1, 0);
		GridPane.setConstraints(lblPassword, 0, 1);
		GridPane.setConstraints(txtPassword, 1, 1);
		GridPane.setHgrow(txtUsername, Priority.ALWAYS);
		GridPane.setHgrow(txtPassword, Priority.ALWAYS);
		return grid;
	}
	
	@Override
	public void load(Pane pane, SledovaniTVCredentials credentials) {
		GridPane grid = (GridPane) pane;
		TextField txtUsername = (TextField) grid.lookup(".field-username");
		PasswordFieldPane txtPassword = (PasswordFieldPane) grid.lookup(".field-password");
		txtUsername.setText(credentials.username());
		txtPassword.setText(credentials.password());
		deviceId = credentials.deviceId();
		profileId = credentials.profileId();
		sessionId = credentials.sessionId();
	}
	
	@Override
	public SledovaniTVCredentials save(Pane pane) {
		GridPane grid = (GridPane) pane;
		TextField txtUsername = (TextField) grid.lookup(".field-username");
		PasswordFieldPane txtPassword = (PasswordFieldPane) grid.lookup(".field-password");
		String username = txtUsername.getText();
		String password = txtPassword.getText();
		return new SledovaniTVCredentials(username, password, deviceId, profileId, sessionId);
	}
}
