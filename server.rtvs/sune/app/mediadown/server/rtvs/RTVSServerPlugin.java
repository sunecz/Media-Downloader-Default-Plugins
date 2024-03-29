package sune.app.mediadown.server.rtvs;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.Servers;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "server.rtvs",
	    title         = "plugin.server.rtvs.title",
	    version       = "00.02.09-0002",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/server/rtvs/",
	    updatable     = true,
	    url           = "https://www.rtvs.sk",
	    icon          = "resources/server/rtvs/icon/rtvs.png")
public final class RTVSServerPlugin extends PluginBase {
	
	private static final String NAME = "rtvs";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		Servers.add(NAME, RTVSServer.class);
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