package sune.app.mediadown.media_engine.tvautosalon;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.MediaEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(
	name    = "media_engine.tvautosalon",
	title   = "plugin.media_engine.tvautosalon.title",
	version = "0.2.9-5.alpha.1",
	author  = "Sune",
	url     = "https://autosalon.tv/",
	icon    = "resources/media_engine/tvautosalon/icon/tvautosalon.png"
)
public final class TVAutosalonEnginePlugin extends PluginBase {
	
	private static final String NAME = "tvautosalon";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, TVAutosalonEngine.class);
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