package sune.app.mediadown.media_engine.novavoyo;

import java.io.IOException;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.authentication.CredentialsManager;
import sune.app.mediadown.authentication.CredentialsMigrator;
import sune.app.mediadown.authentication.EmailCredentials;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.entity.MediaEngines;
import sune.app.mediadown.gui.GUI.CredentialsRegistry;
import sune.app.mediadown.gui.GUI.CredentialsRegistry.CredentialsEntry;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media_engine.novavoyo.gui.OneplayCredentialsType;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Password;

// Note: The website is no longer Nova Voyo, but we keep the same name for backward compatibility
//       and for not having to remove the old plugin. This will be probably changed later.

@Plugin(name          = "media_engine.novavoyo", // Keep the old name
	    title         = "plugin.media_engine.novavoyo.title",
	    version       = "00.02.09-0016",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/novavoyo/", // Keep the old URL
	    updatable     = true,
	    url           = "https://www.oneplay.cz/",
	    icon          = "resources/media_engine/novavoyo/icon/oneplay.png")
public final class OneplayEnginePlugin extends PluginBase {
	
	private static final String NAME = "oneplay";
	
	private String translatedTitle;
	private PluginConfiguration.Builder configuration;
	private boolean credentialsMigrated;
	
	private final void initConfiguration() throws IOException {
		PluginConfiguration.Builder builder
			= new PluginConfiguration.Builder(getContext().getPlugin().instance().name());
		String group = builder.name() + ".general";
		
		if(!CredentialsMigrator.isMigrated(Common.credentialsName())) {
			builder.addProperty(ConfigurationProperty.ofString("authData_email")
				.inGroup(group));
			builder.addProperty(ConfigurationProperty.ofType("authData_password", Password.class)
				.inGroup(group)
				.withTransformer(Password::value, Password::new)
				.withDefaultValue(""));
		}
		
		builder.addProperty(ConfigurationProperty.ofString("token").asHidden(true));
		configuration = builder;
	}
	
	private final void ensureUpdatedCredentials() throws IOException {
		CredentialsManager manager = CredentialsManager.instance();
		String credentialsName = Common.credentialsName();
		
		try(EmailCredentials credentials = (EmailCredentials) manager.get(credentialsName)) {
			if(credentials instanceof OneplayCredentials) {
				return; // Already updated
			}
			
			OneplayCredentials newCredentials = new OneplayCredentials(
				credentials.email(),
				credentials.password(),
				"", "", "" // Do not use null
			);
			
			manager.set(credentialsName, newCredentials);
		}
	}
	
	private final void initCredentials() throws IOException {
		String name = Common.credentialsName();
		
		Translation translation = translation().getTranslation("credentials");
		CredentialsRegistry.registerType(new OneplayCredentialsType(translation));
		
		CredentialsRegistry.registerEntry(
			CredentialsEntry.of(name, translatedTitle, this::getIcon)
		);
		
		if(CredentialsMigrator.isMigrated(name)) {
			ensureUpdatedCredentials();
			return; // Nothing else to do
		}
		
		CredentialsMigrator
			.ofConfiguration(configuration, "authData_email", "authData_password")
			.asCredentials(EmailCredentials.class, String.class, String.class)
			.migrate(name);
		
		ensureUpdatedCredentials();
		credentialsMigrated = true;
	}
	
	@Override
	public void init() throws Exception {
		Common.setPlugin(this);
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, OneplayEngine.class);
		initConfiguration();
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
			NIO.save(configuration.path(), configuration.data().toString());
		}
	}
	
	@Override
	public void dispose() throws Exception {
		Oneplay.instance().dispose();
	}
	
	@Override
	public PluginConfiguration.Builder configuration() {
		return configuration;
	}
	
	@Override
	public String getTitle() {
		return translatedTitle;
	}
}
