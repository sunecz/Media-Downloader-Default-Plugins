package sune.app.mediadown.media_engine.tvbarrandov;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.engine.MediaEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.util.Password;

@Plugin(name          = "media_engine.tvbarrandov",
	    title         = "plugin.media_engine.tvbarrandov.title",
	    version       = "0002",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/tvbarrandov/",
	    updatable     = true,
	    url           = "https://barrandov.tv",
	    icon          = "resources/media_engine/tvbarrandov/icon/tvbarrandov.png")
public final class TVBarrandovEnginePlugin extends PluginBase {
	
	private static final String NAME = "tvbarrandov";
	
	private String translatedTitle;
	private PluginConfiguration.Builder configuration;
	
	private final void initConfiguration() {
		PluginConfiguration.Builder builder
			= new PluginConfiguration.Builder(getContext().getPlugin().instance().name());
		String group = builder.name() + ".general";
		builder.addProperty(ConfigurationProperty.ofString("authData_username")
			.inGroup(group));
		builder.addProperty(ConfigurationProperty.ofType("authData_password", Password.class)
			.inGroup(group)
			.withTransformer(Password::value, Password::new));
		configuration = builder;
	}
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, TVBarrandovEngine.class);
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