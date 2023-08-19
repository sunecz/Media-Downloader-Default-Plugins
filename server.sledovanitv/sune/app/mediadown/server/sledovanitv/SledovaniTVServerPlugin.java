package sune.app.mediadown.server.sledovanitv;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.entity.Servers;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.util.Password;

@Plugin(name          = "server.sledovanitv",
	    title         = "plugin.server.sledovanitv.title",
	    version       = "00.02.09-0001",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/server/sledovanitv/",
	    updatable     = true,
	    url           = "",
	    icon          = "resources/server/sledovanitv/icon/sledovanitv.png")
public final class SledovaniTVServerPlugin extends PluginBase {
	
	private static final String NAME = "sledovanitv";
	
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
		Servers.add(NAME, SledovaniTVServer.class);
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