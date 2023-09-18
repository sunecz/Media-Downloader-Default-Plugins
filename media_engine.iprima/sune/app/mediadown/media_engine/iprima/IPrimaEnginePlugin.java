package sune.app.mediadown.media_engine.iprima;

import static sune.app.mediadown.gui.window.ConfigurationWindow.registerFormField;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.entity.MediaEngines;
import sune.app.mediadown.gui.form.Form;
import sune.app.mediadown.gui.form.FormField;
import sune.app.mediadown.gui.window.ConfigurationWindow.ConfigurationFormFieldProperty;
import sune.app.mediadown.gui.window.ConfigurationWindow.FormFieldSupplier;
import sune.app.mediadown.gui.window.ConfigurationWindow.FormFieldSupplierFactory;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media_engine.iprima.IPrimaAuthenticator.ProfileManager;
import sune.app.mediadown.media_engine.iprima.IPrimaAuthenticator.ProfileManager.Profile;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.plugin.Plugins;
import sune.app.mediadown.util.FXUtils;
import sune.app.mediadown.util.Password;
import sune.app.mediadown.util.Utils;
import sune.util.ssdf2.SSDType;
import sune.util.ssdf2.SSDValue;

@Plugin(name          = "media_engine.iprima",
	    title         = "plugin.media_engine.iprima.title",
	    version       = "00.02.09-0008",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/iprima/",
	    updatable     = true,
	    url           = "https://iprima.cz",
	    icon          = "resources/media_engine/iprima/icon/iprima.png")
public final class IPrimaEnginePlugin extends PluginBase {
	
	private static final String NAME = "iprima";
	
	private static boolean customFieldsRegistered = false;
	
	private String translatedTitle;
	private PluginConfiguration.Builder configuration;
	
	private static final String fullPropertyName(String propertyName) {
		return "media_engine.iprima." + propertyName;
	}
	
	private static final Translation translationOf(String translationPath) {
		return Plugins.getLoaded("media_engine.iprima").getInstance().translation().getTranslation(translationPath);
	}
	
	private final void initConfiguration() {
		PluginConfiguration.Builder builder
			= new PluginConfiguration.Builder(getContext().getPlugin().instance().name());
		String group = builder.name() + ".general";
		
		builder.addProperty(ConfigurationProperty.ofString("authData_email")
			.inGroup(group));
		builder.addProperty(ConfigurationProperty.ofType("authData_password", Password.class)
			.inGroup(group)
			.withTransformer(Password::value, Password::new));
		builder.addProperty(ConfigurationProperty.ofString("profile")
			.inGroup(group)
			.withDefaultValue("auto"));
		
		if(!customFieldsRegistered) {
			registerFormField(ProfileSelectFormFieldSupplierFactory.of(
				fullPropertyName("profile")
			));
			
			customFieldsRegistered = true;
		}
		
		configuration = builder;
	}
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, IPrimaEngine.class);
		initConfiguration();
		IPrimaHelper.setPlugin(PluginLoaderContext.getContext().getInstance());
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
	
	private static final class ProfileSelectFormFieldSupplierFactory implements FormFieldSupplierFactory {
		
		private static Translation translation;
		
		private final String propertyName;
		
		private ProfileSelectFormFieldSupplierFactory(String propertyName) {
			this.propertyName = propertyName;
		}
		
		private static final Translation translation() {
			if(translation == null) {
				translation = translationOf("configuration.values.profile");
			}
			
			return translation;
		}
		
		private static final String valueSave(String audioDeviceAlternativeName) {
			return audioDeviceAlternativeName.replaceAll("\\\\", "/");
		}
		
		private static final String valueLoad(String string) {
			return Utils.removeStringQuotes(string).replaceAll("/", "\\\\");
		}
		
		public static final ProfileSelectFormFieldSupplierFactory of(String propertyName) {
			return new ProfileSelectFormFieldSupplierFactory(propertyName);
		}
		
		private final List<Profile> profiles() throws Exception {
			return ProfileManager.profiles();
		}
		
		private final Profile automaticProfile() {
			return new Profile("auto", translation().getSingle("profile_auto"));
		}
		
		@Override
		public FormFieldSupplier create(String name, ConfigurationFormFieldProperty fieldProperty) {
			return name.equals(propertyName) ? ProfileSelectField::new : null;
		}
		
		private final class ProfileSelectField<T> extends FormField<T> {
			
			private volatile boolean itemsLoaded = false;
			
			private final VBox wrapper;
			private final ComboBox<Profile> control;
			private final Label lblProgress;
			private String loadedValue;
			
			public ProfileSelectField(T property, String name, String title) {
				super(property, name, title);
				wrapper = new VBox(5.0);
				control = new ComboBox<>();
				control.setCellFactory((p) -> new ProfileCell());
				control.setButtonCell(new ProfileCell());
				control.setMaxWidth(Double.MAX_VALUE);
				lblProgress = new Label();
				wrapper.getChildren().addAll(control, lblProgress);
				FXUtils.onWindowShow(control, this::loadItems);
			}
			
			private final void enableSelect(boolean enable) {
				FXUtils.thread(() -> control.setDisable(!enable));
			}
			
			private final void progressText(String text) {
				FXUtils.thread(() -> {
					if(text == null) {
						wrapper.getChildren().remove(lblProgress);
					} else {
						lblProgress.setText(text);
					}
				});
			}
			
			private final void loadItems() {
				Threads.execute(() -> {
					enableSelect(false);
					progressText(translation().getSingle("progress.log_in"));
					
					try {
						IPrimaAuthenticator.SessionData session = IPrimaAuthenticator.getSessionData();
						
						if(session != null) {
							progressText(translation().getSingle("progress.profiles"));
							
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
			
			@Override
			public Node render(Form form) {
				return wrapper;
			}
			
			@Override
			public void value(SSDValue value, SSDType type) {
				loadedValue = valueLoad(value.stringValue());
			}
			
			@Override
			public Object value() {
				return valueSave(
					itemsLoaded
						? selectedProfileId()
						: loadedValue
				);
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