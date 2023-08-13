package sune.app.mediadown.server.youtube;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.Servers;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "server.youtube",
	    title         = "plugin.server.youtube.title",
	    version       = "00.02.09-0005",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/server/youtube/",
	    updatable     = true,
	    url           = "https://youtube.com",
	    icon          = "resources/server/youtube/icon/youtube.png")
public final class YouTubeServerPlugin extends PluginBase {
	
	private static final String NAME = "youtube";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		Servers.add(NAME, YouTubeServer.class);
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