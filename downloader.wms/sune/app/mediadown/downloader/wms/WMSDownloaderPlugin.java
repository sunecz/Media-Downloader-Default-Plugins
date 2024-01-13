package sune.app.mediadown.downloader.wms;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.configuration.ApplicationConfigurationAccessor;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.entity.Downloaders;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;

@Plugin(name          = "downloader.wms",
	    title         = "plugin.downloader.wms.title",
	    version       = "00.02.09-0004",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/downloader/wms/",
	    updatable     = true)
public final class WMSDownloaderPlugin extends PluginBase {
	
	private static final String NAME = "wms";
	
	// Default values of configuration properties
	private static final int     DEFAULT_MAX_RETRY_ATTEMPTS = 1000;
	private static final boolean DEFAULT_ASYNC_TOTAL_SIZE   = true;
	private static final int     DEFAULT_WAIT_ON_RETRY_MS   = 250;
	
	private String translatedTitle;
	private PluginConfiguration.Builder configuration;
	
	private final void initConfiguration() {
		PluginConfiguration.Builder builder
			= new PluginConfiguration.Builder(getContext().getPlugin().instance().name());
		
		builder.addProperty(ConfigurationProperty.ofInteger("maxRetryAttempts")
			.inGroup(ApplicationConfigurationAccessor.GROUP_DOWNLOAD)
			.withDefaultValue(DEFAULT_MAX_RETRY_ATTEMPTS)
			.withOrder(60));
		builder.addProperty(ConfigurationProperty.ofBoolean("asyncTotalSize")
			.inGroup(ApplicationConfigurationAccessor.GROUP_DOWNLOAD)
			.withDefaultValue(DEFAULT_ASYNC_TOTAL_SIZE)
			.withOrder(31));
		builder.addProperty(ConfigurationProperty.ofInteger("waitOnRetryMs")
			.inGroup(ApplicationConfigurationAccessor.GROUP_DOWNLOAD)
			.withDefaultValue(DEFAULT_WAIT_ON_RETRY_MS)
			.withOrder(80));
		
		configuration = builder;
	}
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		Downloaders.add(NAME, WMSDownloader.class);
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