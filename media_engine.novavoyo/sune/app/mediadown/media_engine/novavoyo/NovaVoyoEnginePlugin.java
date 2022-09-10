package sune.app.mediadown.media_engine.novavoyo;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.server.Servers;
import sune.app.mediadown.util.Password;

@Plugin(name          = "media_engine.novavoyo",
	    title         = "plugin.media_engine.novavoyo.title",
	    version       = "0005",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/novavoyo/",
	    updatable     = true,
	    url           = "https://voyo.nova.cz/",
	    icon          = "resources/media_engine/novavoyo/icon/novavoyo.png")
public final class NovaVoyoEnginePlugin extends PluginBase {
	
	private static final String NAME = "novavoyo";
	
	private String translatedTitle;
	private PluginConfiguration.Builder configuration;
	
	private final void initConfiguration() {
		PluginConfiguration.Builder builder
			= new PluginConfiguration.Builder(getContext().getPlugin().instance().name());
		String group = builder.name() + ".general";
		builder.addProperty(ConfigurationProperty.ofString("authData_email")
			.inGroup(group));
		builder.addProperty(ConfigurationProperty.ofType("authData_password", Password.class)
			.inGroup(group)
			.withTransformer(Password::value, Password::new));
		builder.addProperty(ConfigurationProperty.ofArray("devicesToRemove").asHidden(true));
		configuration = builder;
	}
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		// Currently support only obtaining of media sources
		Servers.add(NAME, NovaVoyoServer.class);
		initConfiguration();
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