package sune.app.mediadown.media_engine.iprima;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.engine.MediaEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.util.Password;

@Plugin(name          = "media_engine.iprima",
	    title         = "plugin.media_engine.iprima.title",
	    version       = "0014",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/iprima/",
	    updatable     = true,
	    url           = "https://iprima.cz",
	    icon          = "resources/media_engine/iprima/icon/iprima.png")
public final class IPrimaEnginePlugin extends PluginBase {
	
	private static final String NAME = "iprima";
	
	private String translatedTitle;
	private PluginConfiguration.Builder configuration;
	
	private final void initConfiguration() {
		PluginConfiguration.Builder builder = new PluginConfiguration.Builder(getContext().getPlugin().instance().name());
		builder.addProperty(ConfigurationProperty.ofBoolean("useDefaultAuthData").withDefaultValue(true));
		builder.addProperty(ConfigurationProperty.ofString("authData_email"));
		builder.addProperty(ConfigurationProperty.ofType("authData_password", Password.class)
		                        .withTransformer(Password::value, Password::new));
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
}