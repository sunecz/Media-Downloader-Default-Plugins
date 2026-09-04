package sune.app.mediadown.drm_engine.sledovanitv;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.drm.DRMEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(
	name    = "drm_engine.sledovanitv",
	title   = "plugin.drm_engine.sledovanitv.title",
	version = "0.2.9-3",
	author  = "Sune",
	url     = "https://sledovanitv.cz/",
	icon    = "resources/drm_engine/sledovanitv/icon/sledovanitv.png"
)
public final class SledovaniTVDRMEnginePlugin extends PluginBase {
	
	private static final String NAME = "sledovanitv";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		DRMEngines.add(NAME, SledovaniTVDRMEngine.class);
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