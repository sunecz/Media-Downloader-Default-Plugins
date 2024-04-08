package sune.app.mediadown.drm_engine.novaplus;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.drm.DRMEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "drm_engine.novaplus",
	    title         = "plugin.drm_engine.novaplus.title",
	    version       = "00.02.09-0002",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/drm_engine/novaplus/",
	    updatable     = true,
	    url           = "https://tv.nova.cz/",
	    icon          = "resources/drm_engine/novaplus/icon/novaplus.png")
public final class NovaPlusDRMEnginePlugin extends PluginBase {
	
	private static final String NAME = "novaplus";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		DRMEngines.add(NAME, NovaPlusDRMEngine.class);
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