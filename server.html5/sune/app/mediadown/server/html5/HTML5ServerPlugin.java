package sune.app.mediadown.server.html5;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.Servers;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(
	name    = "server.html5",
	title   = "plugin.server.html5.title",
	version = "0.2.9-1.alpha.1",
	author  = "Sune",
	url     = "",
	icon    = "resources/server/html5/icon/html5.png"
)
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