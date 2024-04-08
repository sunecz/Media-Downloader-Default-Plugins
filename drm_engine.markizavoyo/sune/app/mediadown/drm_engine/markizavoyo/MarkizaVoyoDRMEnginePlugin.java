package sune.app.mediadown.drm_engine.markizavoyo;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.drm.DRMEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "drm_engine.markizavoyo",
	    title         = "plugin.drm_engine.markizavoyo.title",
	    version       = "00.02.09-0002",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/drm_engine/markizavoyo/",
	    updatable     = true,
	    url           = "https://voyo.markiza.sk/",
	    icon          = "resources/drm_engine/markizavoyo/icon/markizavoyo.png")
public final class MarkizaVoyoDRMEnginePlugin extends PluginBase {
	
	private static final String NAME = "markizavoyo";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		DRMEngines.add(NAME, MarkizaVoyoDRMEngine.class);
	}
	
	@Override
	public void dispose() throws Exception {
		// Do nothing
	}
	
	@Override
	public String getTitle() {
		return translatedTitle;
	}
}