package sune.app.mediadown.server.direct;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.Servers;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "server.direct",
	    title         = "plugin.server.direct.title",
	    version       = "00.02.09-0001",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/server/direct/",
	    updatable     = true,
	    url           = "",
	    icon          = "resources/server/direct/icon/direct.png")
public final class DirectServerPlugin extends PluginBase {
	
	private static final String NAME = "direct";
	private String translatedTitle;
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		Servers.add(NAME, DirectServer.class);
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