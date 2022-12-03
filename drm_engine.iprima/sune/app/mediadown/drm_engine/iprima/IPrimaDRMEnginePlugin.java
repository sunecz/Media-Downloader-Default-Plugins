package sune.app.mediadown.drm_engine.iprima;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadownloader.drm.DRMEngines;

@Plugin(name          = "drm_engine.iprima",
	    title         = "plugin.drm_engine.iprima.title",
	    version       = "00.02.08-0000",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/drm_engine/iprima/",
	    updatable     = true,
	    url           = "https://iprima.cz/",
	    icon          = "resources/drm_engine/iprima/icon/iprima.png")
public final class IPrimaDRMEnginePlugin extends PluginBase {
	
	private static final String NAME = "iprima";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		DRMEngines.add(NAME, IPrimaDRMEngine.class);
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