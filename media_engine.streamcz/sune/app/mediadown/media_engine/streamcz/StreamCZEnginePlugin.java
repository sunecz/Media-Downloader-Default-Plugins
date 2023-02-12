package sune.app.mediadown.media_engine.streamcz;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.MediaEngines;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "media_engine.streamcz",
	    title         = "plugin.media_engine.streamcz.title",
	    version       = "00.02.08-0003",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/media_engine/streamcz/",
	    updatable     = true,
	    url           = "https://www.stream.cz/",
	    icon          = "resources/media_engine/streamcz/icon/streamcz.png")
public final class StreamCZEnginePlugin extends PluginBase {
	
	private static final String NAME = "streamcz";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		MediaEngines.add(NAME, StreamCZEngine.class);
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