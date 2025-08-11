package sune.app.mediadown.server.sledovanitv;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.gui.Dialog;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.CheckedConsumer;

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
	
	public static final void error(Throwable throwable) {
		if(throwable == null) {
			return;
		}
		
		if(throwable instanceof AuthenticationException
				|| (throwable != null && throwable.getCause() instanceof AuthenticationException)) {
			Translation tr = translation().getTranslation("error.authentication");
			Dialog.showError(tr.getSingle("title"), tr.getSingle("text"));
			return; // Do not continue
		}
		
		Translation tr = translation().getTranslation("error.message_error");
		Dialog.showError(tr.getSingle("title"), throwable.getMessage());
	}
	
	public static final <T> CheckedConsumer<ListTask<T>> handleErrors(
		CheckedConsumer<ListTask<T>> action
	) {
		return ((task) -> {
			try {
				action.accept(task);
			} catch(Exception ex) {
				// More user-friendly error messages
				error(ex);
			}
		});
	}
	
	public static final class AuthenticationException extends Exception {
		
		private static final long serialVersionUID = -9066662980097786463L;
		
		public AuthenticationException() {
			super();
		}
		
		public AuthenticationException(String message) {
			super(message);
		}
	}
}
