package sune.app.mediadown.media_engine.markizaplus;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.MediaEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "media_engine.markizaplus",
	    title         = "plugin.media_engine.markizaplus.title",
	    version       = "00.02.09-0006",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/markizaplus/",
	    updatable     = true,
	    url           = "https://videoarchiv.markiza.sk/",
	    icon          = "resources/media_engine/markizaplus/icon/markizaplus.png")
public final class MarkizaPlusEnginePlugin extends PluginBase {
	
	private static final String NAME = "markizaplus";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, MarkizaPlusEngine.class);
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