package sune.app.mediadown.media_engine.tncz;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.engine.MediaEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "media_engine.tncz",
	    title         = "plugin.media_engine.tncz.title",
	    version       = "00.02.08-0000",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/tncz/",
	    updatable     = true,
	    url           = "https://tn.nova.cz/",
	    icon          = "resources/media_engine/tncz/icon/tncz.png")
public final class TNCZEnginePlugin extends PluginBase {
	
	private static final String NAME = "tncz";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, TNCZEngine.class);
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