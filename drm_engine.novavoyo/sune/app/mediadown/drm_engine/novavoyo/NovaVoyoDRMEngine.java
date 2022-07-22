package sune.app.mediadown.drm_engine.novavoyo;

import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.cef.browser.CefFrame;
import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;

import io.netty.handler.codec.http.FullHttpRequest;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.util.Reflection2;
import sune.app.mediadown.util.Reflection3;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadownloader.drm.DRMBrowser;
import sune.app.mediadownloader.drm.DRMContext;
import sune.app.mediadownloader.drm.DRMEngine;
import sune.app.mediadownloader.drm.DRMResolver;
import sune.app.mediadownloader.drm.resolver.SimpleDRMResolver;
import sune.app.mediadownloader.drm.util.JS;
import sune.app.mediadownloader.drm.util.MPDQualityModifier;

public class NovaVoyoDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	NovaVoyoDRMEngine() {
	}
	
	private static final boolean doVoyoLogin() throws Exception {
		Class<?> clazz = Class.forName("sune.app.mediadown.media_engine.novavoyo.NovaVoyoServer$VoyoAccount");
		return (boolean) Reflection3.invokeStatic(clazz, "login");
	}
	
	private static final CookieManager ensureCookieManager() throws Exception {
		Reflection3.invokeStatic(Web.class, "ensureCookieManager");
		return (CookieManager) Reflection2.getField(Web.class, null, "COOKIE_MANAGER");
	}
	
	private static final List<HttpCookie> savedCookies() throws Exception {
		URI uri = Utils.uri("https://voyo.nova.cz/muj-profil");
		
		// Get the top-level domain so that all the cookies are included
		String domain = uri.getHost();
		String[] parts = domain.split("\\.", 3);
		if(parts.length < 2)
			throw new IllegalStateException("Invalid domain");
		int i = parts.length < 3 ? 0 : 1;
		domain = parts[i] + '.' + parts[i + 1];
		
		String tlDomain = domain;
		CookieStore cookieStore = ensureCookieManager().getCookieStore();
		return cookieStore.getCookies().stream()
			.filter((c) -> c.getDomain().endsWith(tlDomain))
			.collect(Collectors.toList());
	}
	
	private static final void setCefCookies(String url, List<HttpCookie> cookies) {
		Instant instant = Instant.now();
		Date now = Date.from(instant);
		Date expires = Date.from(instant.plus(7, ChronoUnit.DAYS));
		CefCookieManager cookieManager = CefCookieManager.getGlobalManager();
		for(HttpCookie cookie : cookies) {
			CefCookie cefCookie = new CefCookie(cookie.getName(), cookie.getValue(), cookie.getDomain(),
				cookie.getPath(), cookie.getSecure(), cookie.isHttpOnly(), now, now, false, expires);
			cookieManager.setCookie(url, cefCookie);
		}
	}
	
	@Override
	public DRMResolver createResolver(DRMContext context, String url, Path output, MediaQuality quality) {
		return new NovaVoyoDRMResolver(context, url, output, quality);
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
		String host = urlObj.getHost();
		if((host.startsWith("www."))) // www prefix
			host = host.substring(4);
		if(!host.equals("voyo.nova.cz"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	private static final class NovaVoyoDRMResolver extends SimpleDRMResolver {
		
		private static final String URL_IFRAME;
		
		static {
			URL_IFRAME = "https://media.cms.nova.cz/embed/";
		}
		
		public NovaVoyoDRMResolver(DRMContext context, String url, Path output, MediaQuality quality) {
			super(context, url, output, quality);
		}
		
		@Override
		public void onLoadStart(DRMBrowser browser, CefFrame frame) {
			if(frame.getURL().startsWith(url)) {
				// Obtain all required login cookies and do "headless" login.
				Utils.ignoreWithCheck(() -> {
					doVoyoLogin();
					setCefCookies(url, savedCookies());
				}, MediaDownloader::error);
				
				// Set consent cookie to not show the cookie consent popup.
				Instant instant = Instant.now();
				Date now = Date.from(instant);
				Date expires = Date.from(instant.plus(7, ChronoUnit.DAYS));
				// This cookie value means "Decline all unnecessary cookies".
				String value = "CPcJswAPcJswAAHABBENCXCgAAAAAAAAAAAAAAAAAAEiIAMAAQRwJQAYAA"
						+ "gjgGgAwABBHAVABgACCOBSADAAEEcB0AGAAII4EIAMAAQRwCQAYAAgjgMgAwABBHAA"
						+ ".YAAAAAAAAAAA";
				CefCookie cookieConsent = new CefCookie("euconsent-v2", value,
					".nova.cz", "/", false, false, now, now, false, expires);
				CefCookieManager.getGlobalManager().setCookie(url, cookieConsent);
			} else if(frame.getURL().startsWith(URL_IFRAME)) {
				JS.Record.include(frame);
				JS.Record.activate(frame, ".video-js[id^=\"player-\"] > video");
			}
		}
		
		@Override
		public void onLoadEnd(DRMBrowser browser, CefFrame frame, int httpStatusCode) {
			if(frame.getURL().startsWith(url)) {
				JS.Helper.include(frame);
				// Bring the embedded iframe's content to the top of the stack context,
				// so that we can work with it later.
				JS.Helper.includeStyle(frame, ".iframe-wrap>iframe{z-index:2147483600!important;}");
			} else if(frame.getURL().startsWith(URL_IFRAME)) {
				JS.Helper.include(frame);
				JS.Helper.hideVideoElementStyle(frame);
			}
		}
		
		@Override
		public boolean shouldModifyRequest(FullHttpRequest request) {
			return request.getUri().endsWith("/manifest.mpd");
		}
		
		@Override
		public void modifyRequest(FullHttpRequest request) {
			// Disable encoding for specific requests
			request.headers().set("Accept-Encoding", "identity");
		}
		
		@Override
		public boolean shouldModifyResponse(String uri, String mimeType, Charset charset) {
			return mimeType.equalsIgnoreCase("application/dash+xml");
		}
		
		@Override
		public String modifyResponse(String uri, String mimeType, Charset charset, String content) {
			if(mimeType.equalsIgnoreCase("application/dash+xml")) {
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