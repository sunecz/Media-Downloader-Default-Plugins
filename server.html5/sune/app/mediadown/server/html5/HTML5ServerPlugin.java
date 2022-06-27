package sune.app.mediadown.server.html5;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.server.Servers;

@Plugin(name          = "server.html5",
	    title         = "plugin.server.html5.title",
	    version       = "0002",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/server/html5/",
	    updatable     = true,
	    url           = "",
	    icon          = "resources/server/html5/icon/html5.png")
public final class HTML5ServerPlugin extends PluginBase {
	
	private static final String NAME = "html5";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		Servers.add(NAME, HTML5Server.class);
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