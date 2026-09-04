package sune.app.mediadown.drm_engine.iprima;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.drm.DRMEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(
	name    = "drm_engine.iprima",
	title   = "plugin.drm_engine.iprima.title",
	version = "0.2.9-3",
	author  = "Sune",
	url     = "https://iprima.cz/",
	icon    = "resources/drm_engine/iprima/icon/iprima.png"
)
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