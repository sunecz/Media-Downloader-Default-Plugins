package sune.app.mediadown.media_engine.tvprimadoma;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.MediaEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "media_engine.tvprimadoma",
	    title         = "plugin.media_engine.tvprimadoma.title",
	    version       = "00.02.08-0005",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/tvprimadoma/",
	    updatable     = true,
	    url           = "https://primadoma.tv/",
	    icon          = "resources/media_engine/tvprimadoma/icon/tvprimadoma.png")
public final class TVPrimaDomaEnginePlugin extends PluginBase {
	
	private static final String NAME = "tvprimadoma";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, TVPrimaDomaEngine.class);
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