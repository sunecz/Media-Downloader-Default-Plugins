package sune.app.mediadown.media_engine.ceskatelevize;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.MediaEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(
	name    = "media_engine.ceskatelevize",
	title   = "plugin.media_engine.ceskatelevize.title",
	version = "0.2.9-13.alpha.1",
	author  = "Sune",
	url     = "https://ceskatelevize.cz",
	icon    = "resources/media_engine/ceskatelevize/icon/ceskatelevize.png"
)
public final class CeskaTelevizeEnginePlugin extends PluginBase {
	
	private static final String NAME = "ceskatelevize";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, CeskaTelevizeEngine.class);
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