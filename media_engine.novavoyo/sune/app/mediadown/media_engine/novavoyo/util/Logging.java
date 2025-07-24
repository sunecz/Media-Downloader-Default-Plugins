package sune.app.mediadown.media_engine.novavoyo.util;

import java.util.logging.Level;

import sune.app.mediadown.logging.Log;

public final class Logging {
	
	private static volatile boolean initialized;
	private static Log log;
	
	private Logging() {
	}
	
	public static final void initialize(Level logLevel) {
		if(initialized) {
			return; // Ignore subsequent initializations
		}
		
		log = Log.initialize("plugin:media_engine.novavoyo", "media_engine-novavoyo.log", logLevel);
		initialized = true;
	}
	
	public static final Log log() {
		return log;
	}
	
	public static final void logDebug(String message, Object... args) {
		Log log;
		if((log = Logging.log) == null) {
			return;
		}
		
		log.debug(message, args);
	}
}
