package sune.app.mediadown.server.rtvs;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.Servers;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "server.rtvs",
	    title         = "plugin.server.rtvs.title",
	    version       = "00.02.09-0004",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/server/rtvs/",
	    updatable     = true,
	    url           = "https://www.stvr.sk/",
	    icon          = "resources/server/rtvs/icon/stvr.png")
public final class STVRServerPlugin extends PluginBase {
	
	private static final String NAME = "stvr";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		Servers.add(NAME, STVRServer.class);
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