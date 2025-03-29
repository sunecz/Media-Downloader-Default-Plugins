package sune.app.mediadown.media_engine.novavoyo;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.gui.Dialog;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.CheckedConsumer;
import sune.app.mediadown.util.CheckedRunnable;
import sune.app.mediadown.util.CheckedSupplier;
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
				// More user-friendly error messages
				error(ex);
			}
		});
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
	
	public static final <T> T handleErrors(CheckedSupplier<T> action) throws Exception {
		try {
			return action.get();
		} catch(Exception ex) {
			// More user-friendly error messages
			error(ex);
			throw ex;
		}
	}
	
	public static final void error(Throwable throwable) {
		if(throwable == null) {
			return;
		}
		
		Throwable th = null;
		
		if((th = throwable) instanceof TranslatableException
				|| (th = throwable.getCause()) instanceof TranslatableException) {
			TranslatableException tex = (TranslatableException) th;
			Translation tr = translation().getTranslation(tex.translationPath());
			Dialog.showError(tr.getSingle("title"), tr.getSingle("text"));
			return; // Do not continue
		}
		
		if((th = throwable) instanceof MessageException
				|| (th = throwable.getCause()) instanceof MessageException) {
			MessageException mex = (MessageException) th;
			Translation tr = translation().getTranslation("error.message_error");
			Dialog.showError(tr.getSingle("title"), mex.message());
			return; // Do not continue
		}
		
		MediaDownloader.error(throwable); // Fallback
	}
	
	public static final class TranslatableException extends Exception {
		
		private static final long serialVersionUID = 8186125132950464214L;
		
		protected final String translationPath;
		
		public TranslatableException(String translationPath) {
			super();
			this.translationPath = Objects.requireNonNull(translationPath);
		}
		
		public TranslatableException(String translationPath, Throwable cause) {
			super(cause);
			this.translationPath = Objects.requireNonNull(translationPath);
		}
		
		public String translationPath() {
			return translationPath;
		}
	}
	
	public static class MessageException extends Exception {
		
		private static final long serialVersionUID = -6400459834129159399L;
		
		protected final String message;
		
		public MessageException(String message) {
			super(message);
			this.message = Objects.requireNonNull(message);
		}
		
		public MessageException(String message, Throwable cause) {
			super(message, cause);
			this.message = Objects.requireNonNull(message);
		}
		
		public String message() {
			return message;
		}
	}
}
