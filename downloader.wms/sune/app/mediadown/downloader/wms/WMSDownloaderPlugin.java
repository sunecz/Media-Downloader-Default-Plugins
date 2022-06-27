package sune.app.mediadown.downloader.wms;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.download.Downloaders;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;

@Plugin(name          = "downloader.wms",
	    title         = "plugin.downloader.wms.title",
	    version       = "0006",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/downloader/wms/",
	    updatable     = true)
public final class WMSDownloaderPlugin extends PluginBase {
	
	private static final String NAME = "wms";
	
	// Default values of configuration properties
	private static final int     DEFAULT_MAX_RETRY_ATTEMPTS = 1000;
	private static final boolean DEFAULT_ASYNC_TOTAL_SIZE   = true;
	
	private String translatedTitle;
	private PluginConfiguration.Builder configuration;
	
	private final void initConfiguration() {
		PluginConfiguration.Builder builder = new PluginConfiguration.Builder(getContext().getPlugin().instance().name());
		builder.addProperty(ConfigurationProperty.ofInteger("maxRetryAttempts").withDefaultValue(DEFAULT_MAX_RETRY_ATTEMPTS));
		builder.addProperty(ConfigurationProperty.ofBoolean("asyncTotalSize").withDefaultValue(DEFAULT_ASYNC_TOTAL_SIZE));
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