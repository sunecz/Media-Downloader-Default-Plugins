package sune.app.mediadown.drm_engine.ceskatelevize;

import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.cef.browser.CefFrame;
import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;

import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.util.Utils;
import sune.app.mediadownloader.drm.DRMBrowser;
import sune.app.mediadownloader.drm.DRMContext;
import sune.app.mediadownloader.drm.DRMEngine;
import sune.app.mediadownloader.drm.DRMResolver;
import sune.app.mediadownloader.drm.resolver.SimpleDRMResolver;
import sune.app.mediadownloader.drm.util.JS;
import sune.app.mediadownloader.drm.util.MPDQualityModifier;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDF;

public class CeskaTelevizeDRMEngine implements DRMEngine {
	
	@Override
	public DRMResolver createResolver(DRMContext context, String url, Path output, MediaQuality quality) {
		return new CTDRMResolver(context, url, output, quality);
	}
	
	@Override
	public boolean isCompatibleURL(String url) {
		URL urlObj = Utils.url(url);
		// Check the protocol
		String protocol = urlObj.getProtocol();
		if(!protocol.equals("http") &&
		   !protocol.equals("https"))
			return false;
		// Check the host
		String[] hostParts = urlObj.getHost().split("\\.", 2);
		if(hostParts.length < 2
				// Check only the second and top level domain names,
				// since there are many subdomains, and there may be
				// possibly even more in the future.
				|| !hostParts[1].equalsIgnoreCase("ceskatelevize.cz"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	private static final class CTDRMResolver extends SimpleDRMResolver {
		
		private static final String URL_IFRAME;
		
		static {
			URL_IFRAME = "https://www.ceskatelevize.cz/ivysilani/embed/iFramePlayer.php";
		}
		
		public CTDRMResolver(DRMContext context, String url, Path output, MediaQuality quality) {
			super(context, url, output, quality);
		}
		
		@Override
		public void onLoadStart(DRMBrowser browser, CefFrame frame) {
			if(frame.getURL().startsWith(url)) {
				// Set consent cookie to not show the cookie consent popup.
				Instant instant = Instant.now();
				String currentTimestamp = instant.toString();
				Date now = Date.from(instant);
				Date expires = Date.from(instant.plus(24, ChronoUnit.HOURS));
				CefCookie cookieConsent = new CefCookie("OptanonAlertBoxClosed", currentTimestamp,
					".ceskatelevize.cz", "/", false, false, now, now, false, expires);
				CefCookieManager.getGlobalManager().setCookie(url, cookieConsent);
			} else if(frame.getURL().startsWith(URL_IFRAME)) {
				JS.Record.include(frame);
				JS.Record.activate(frame, "#video");
			}
		}
		
		@Override
		public void onLoadEnd(DRMBrowser browser, CefFrame frame, int httpStatusCode) {
			if(frame.getURL().startsWith(url)) {
				JS.Helper.include(frame);
				JS.Helper.click(browser, frame, "main button");
			} else if(frame.getURL().startsWith(URL_IFRAME)) {
				JS.Helper.include(frame);
				JS.Helper.hideVideoElementStyle(frame);
			}
		}
		
		@Override
		public boolean shouldModifyResponse(String uri, String mimeType, Charset charset) {
			return uri.contains("ivysilani/client-playlist/") || mimeType.equalsIgnoreCase("application/dash+xml");
		}
		
		@Override
		public String modifyResponse(String uri, String mimeType, Charset charset, String content) {
			if(uri.contains("ivysilani/client-playlist/")) {
				// Remove ads from the playlist JSON data, so the recording is not interrupted
				SSDCollection json = SSDF.readJSON(content);
				json.set("setup.vast.preRoll", SSDCollection.emptyArray());
				json.set("setup.vast.midRoll", SSDCollection.emptyArray());
				json.set("setup.vast.postRoll", SSDCollection.emptyArray());
				content = json.toJSON(true);
			} else if(mimeType.equalsIgnoreCase("application/dash+xml")) {
				// Select the quality we want
				MPDQualityModifier modifier = MPDQualityModifier.fromString(content);
				modifier.modify(quality);
				content = modifier.xml().html();
			}
			return content;
		}
		
		@Override
		public String url() {
			return url;
		}
	}
}