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

import io.netty.handler.codec.http.FullHttpRequest;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.util.JSON;
import sune.app.mediadownloader.drm.DRMBrowser;
import sune.app.mediadownloader.drm.DRMContext;
import sune.app.mediadownloader.drm.DRMEngine;
import sune.app.mediadownloader.drm.DRMResolver;
import sune.app.mediadownloader.drm.resolver.SimpleDRMResolver;
import sune.app.mediadownloader.drm.util.JS;
import sune.app.mediadownloader.drm.util.MPDQualityModifier;
import sune.util.ssdf2.SSDCollection;

public class CeskaTelevizeDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	CeskaTelevizeDRMEngine() {
	}
	
	@Override
	public DRMResolver createResolver(DRMContext context, String url, Path output, Media media) {
		return new CTDRMResolver(context, url, output, media);
	}
	
	@Override
	public boolean isCompatibleURL(String url) {
		URL urlObj = Net.url(url);
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
			URL_IFRAME = "https://player.ceskatelevize.cz/";
		}
		
		public CTDRMResolver(DRMContext context, String url, Path output, Media media) {
			super(context, url, output, media);
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
				JS.Record.activate(frame, "video[class^='video-']");
			}
		}
		
		@Override
		public void onLoadEnd(DRMBrowser browser, CefFrame frame, int httpStatusCode) {
			if(frame.getURL().startsWith(url)) {
				JS.Helper.include(frame);
				JS.Helper.enableInterframeCommunication(frame);
				JS.Helper.click(browser, frame, "main button");
			} else if(frame.getURL().startsWith(URL_IFRAME)) {
				JS.Helper.include(frame);
				JS.Helper.hideVideoElementStyle(frame);
			}
		}
		
		@Override
		public boolean shouldModifyResponse(String uri, String mimeType, Charset charset, FullHttpRequest request) {
			return uri.contains("ivysilani/client-playlist/") || mimeType.equalsIgnoreCase("application/dash+xml");
		}
		
		@Override
		public String modifyResponse(String uri, String mimeType, Charset charset, String content,
				FullHttpRequest request) {
			if(uri.contains("ivysilani/client-playlist/")) {
				SSDCollection json = JSON.read(content);
				
				// Remove ads from the playlist JSON data, so the recording is not interrupted
				json.set("setup.vast.preRoll", SSDCollection.emptyArray());
				json.set("setup.vast.midRoll", SSDCollection.emptyArray());
				json.set("setup.vast.postRoll", SSDCollection.emptyArray());
				
				// Keep only VODs (the actual videos) in the playlist
				SSDCollection newPlaylist = SSDCollection.emptyArray();
				for(SSDCollection item : json.getDirectCollection("playlist").collectionsIterable()) {
					String type = item.getDirectString("type");
					if(type.equals("VOD")) newPlaylist.add(item);
				}
				json.set("playlist", newPlaylist);
				
				content = json.toJSON(true);
			} else if(mimeType.equalsIgnoreCase("application/dash+xml")) {
				// Select the quality we want
				MPDQualityModifier modifier = MPDQualityModifier.fromString(content);
				modifier.modify(media.quality());
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