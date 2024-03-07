package sune.app.mediadown.downloader.wms;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.configuration.ApplicationConfigurationAccessor;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.entity.Downloaders;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.update.Version;

@Plugin(name          = "downloader.wms",
	    title         = "plugin.downloader.wms.title",
	    version       = "00.02.09-0008",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/downloader/wms/",
	    updatable     = true)
public final class WMSDownloaderPlugin extends PluginBase {
	
	private static final String NAME = "wms";
	
	// Default values of configuration properties
	private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 500;
	private static final int DEFAULT_WAIT_ON_RETRY_MS   = 250;
	
	private String translatedTitle;
	private PluginConfiguration.Builder configuration;
	
	private final void initConfiguration() {
		PluginConfiguration.Builder builder
			= new PluginConfiguration.Builder(getContext().getPlugin().instance().name());
		
		builder.addProperty(ConfigurationProperty.ofInteger("maxRetryAttempts")
			.inGroup(ApplicationConfigurationAccessor.GROUP_DOWNLOAD)
			.withDefaultValue(DEFAULT_MAX_RETRY_ATTEMPTS)
			.withOrder(60));
		builder.addProperty(ConfigurationProperty.ofInteger("waitOnRetryMs")
			.inGroup(ApplicationConfigurationAccessor.GROUP_DOWNLOAD)
			.withDefaultValue(DEFAULT_WAIT_ON_RETRY_MS)
			.withOrder(80));
		
		configuration = builder;
	}
	
	private final void initUpdateTriggers() {
		PluginBase plugin = PluginLoaderContext.getContext().getInstance();
		
		// Update maxRetryAttempts to the new default value
		MediaDownloader.UpdateTriggers.OfPlugin.add(
			plugin.getContext().getPlugin().instance().name(),
			Version.ZERO,
			Version.of("00.02.09-0008"),
			() -> {
				PluginConfiguration configuration = plugin.getContext().getConfiguration();
				final int oldDefaultValue = 1000;
				final int newDefaultValue = DEFAULT_MAX_RETRY_ATTEMPTS;
				int currentValue = configuration.intValue("maxRetryAttempts");
				
				if(currentValue == oldDefaultValue) {
					configuration.writer().set("maxRetryAttempts", newDefaultValue);
				}
			}
		);
	}
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		Downloaders.add(NAME, WMSDownloader.class);
		initConfiguration();
		initUpdateTriggers();
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