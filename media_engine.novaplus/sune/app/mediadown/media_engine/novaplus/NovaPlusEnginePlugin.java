package sune.app.mediadown.media_engine.novaplus;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.engine.MediaEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "media_engine.novaplus",
	    title         = "plugin.media_engine.novaplus.title",
	    version       = "0009",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/novaplus/",
	    updatable     = true,
	    url           = "https://tv.nova.cz/",
	    icon          = "resources/media_engine/novaplus/icon/novaplus.png")
public final class NovaPlusEnginePlugin extends PluginBase {
	
	private static final String NAME = "novaplus";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, NovaPlusEngine.class);
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