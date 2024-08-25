package sune.app.mediadown.downloader.wms.common;

import java.net.http.HttpHeaders;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.net.Web;

public final class Common {
	
	public static final HttpHeaders HEADERS = Web.Headers.ofSingle("Accept", "*/*");
	
	// Forbid anyone to create an instance of this class
	private Common() {
	}
	
	public static final Translation translation() {
		return MediaDownloader.translation().getTranslation("plugin.downloader.wms");
	}
}