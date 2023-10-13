package sune.app.mediadown.media_engine.markizavoyo;

import java.io.IOException;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.authentication.CredentialsMigrator;
import sune.app.mediadown.authentication.EmailCredentials;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.entity.MediaEngines;
import sune.app.mediadown.gui.GUI.CredentialsRegistry;
import sune.app.mediadown.gui.GUI.CredentialsRegistry.CredentialsEntry;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.util.NIO;
import sune.app.mediadown.util.Password;

@Plugin(name          = "media_engine.markizavoyo",
	    title         = "plugin.media_engine.markizavoyo.title",
	    version       = "00.02.09-0003",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/markizavoyo/",
	    updatable     = true,
	    url           = "https://voyo.markiza.sk/",
	    icon          = "resources/media_engine/markizavoyo/icon/markizavoyo.png")
public final class MarkizaVoyoEnginePlugin extends PluginBase {
	
	private static final String NAME = "markizavoyo";
	
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
				.withTransformer(Password::value, Password::new));
		}
		
		builder.addProperty(ConfigurationProperty.ofArray("devicesToRemove").asHidden(true));
		configuration = builder;
	}
	
	private final String credentialsName() {
		return "plugin/" + getContext().getPlugin().instance().name().replace('.', '/');
	}
	
	private final void initCredentials() throws IOException {
		String name = credentialsName();
		
		CredentialsRegistry.registerEntry(
			CredentialsEntry.of(name, translatedTitle, this::getIcon)
		);
		
		if(CredentialsMigrator.isMigrated(name)) {
			return; // Nothing to do
		}
		
		CredentialsMigrator
			.ofConfiguration(configuration, "authData_email", "authData_password")
			.asCredentials(EmailCredentials.class, String.class, String.class)
			.migrate(name);
		
		credentialsMigrated = true;
	}
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, MarkizaVoyoEngine.class);
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
}