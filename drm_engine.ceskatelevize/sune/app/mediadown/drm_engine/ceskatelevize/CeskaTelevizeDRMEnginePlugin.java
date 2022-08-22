package sune.app.mediadown.drm_engine.ceskatelevize;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadownloader.drm.DRMEngines;

@Plugin(name          = "drm_engine.ceskatelevize",
	    title         = "plugin.drm_engine.ceskatelevize.title",
	    version       = "0003",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/drm_engine/ceskatelevize/",
	    updatable     = true,
	    url           = "https://ceskatelevize.cz",
	    icon          = "resources/drm_engine/ceskatelevize/icon/ceskatelevize.png")
public final class CeskaTelevizeDRMEnginePlugin extends PluginBase {
	
	private static final String NAME = "ceskatelevize";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		DRMEngines.add(NAME, CeskaTelevizeDRMEngine.class);
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