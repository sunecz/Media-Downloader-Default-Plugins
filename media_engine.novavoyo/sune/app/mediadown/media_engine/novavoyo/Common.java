package sune.app.mediadown.media_engine.novavoyo;

import java.util.UUID;
import java.util.stream.Stream;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.util.CheckedRunnable;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.Utils;

public final class Common {
	
	private static PluginBase PLUGIN;
	
	private Common() {
	}
	
	public static final void setPlugin(PluginBase plugin) {
		PLUGIN = plugin;
	}
	
	public static final Translation translation() {
		String path = "plugin." + PLUGIN.getContext().getPlugin().instance().name();
		return MediaDownloader.translation().getTranslation(path);
	}
	
	public static final PluginConfiguration configuration() {
		return PLUGIN.getContext().getConfiguration();
	}
	
	public static final String credentialsName() {
		return "plugin/" + PLUGIN.getContext().getPlugin().instance().name().replace('.', '/');
	}
	
	public static final String newUUID() {
		return UUID.randomUUID().toString();
	}
	
	public static final Stream<JSONCollection> asCollectionStream(JSONCollection coll) {
		return Utils.stream(coll.collectionsIterable());
	}
	
	public static final Runnable handleErrors(CheckedRunnable action) {
		return (() -> {
			try {
				action.run();
			} catch(Exception ex) {
				MediaDownloader.error(ex);
			}
		});
	}
}
