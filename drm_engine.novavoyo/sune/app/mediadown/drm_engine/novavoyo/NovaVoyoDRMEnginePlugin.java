package sune.app.mediadown.drm_engine.novavoyo;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadownloader.drm.DRMEngines;

@Plugin(name          = "drm_engine.novavoyo",
	    title         = "plugin.drm_engine.novavoyo.title",
	    version       = "00.02.08-0003",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/drm_engine/novavoyo/",
	    updatable     = true,
	    url           = "https://voyo.nova.cz/",
	    icon          = "resources/drm_engine/novavoyo/icon/novavoyo.png")
public final class NovaVoyoDRMEnginePlugin extends PluginBase {
	
	private static final String NAME = "novavoyo";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		DRMEngines.add(NAME, NovaVoyoDRMEngine.class);
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