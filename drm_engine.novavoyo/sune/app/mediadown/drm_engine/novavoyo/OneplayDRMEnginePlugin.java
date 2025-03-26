package sune.app.mediadown.drm_engine.novavoyo;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.drm.DRMEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

//Note: The website is no longer Nova Voyo, but we keep the same name for backward compatibility
//      and for not having to remove the old plugin. This will be probably changed later.

@Plugin(name          = "drm_engine.novavoyo", // Keep the old name
	    title         = "plugin.drm_engine.novavoyo.title",
	    version       = "00.02.09-0003",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/drm_engine/novavoyo/", // Keep the old URL
	    updatable     = true,
	    url           = "https://www.oneplay.cz/",
	    icon          = "resources/drm_engine/novavoyo/icon/oneplay.png")
public final class OneplayDRMEnginePlugin extends PluginBase {
	
	private static final String NAME = "oneplay";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		DRMEngines.add(NAME, OneplayDRMEngine.class);
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
