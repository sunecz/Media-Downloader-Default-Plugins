package sune.app.mediadown.drm_engine.markizavoyo;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.drm.DRMEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(
	name    = "drm_engine.markizavoyo",
	title   = "plugin.drm_engine.markizavoyo.title",
	version = "0.2.9-3.alpha.1",
	author  = "Sune",
	url     = "https://voyo.markiza.sk/",
	icon    = "resources/drm_engine/markizavoyo/icon/markizavoyo.png"
)
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