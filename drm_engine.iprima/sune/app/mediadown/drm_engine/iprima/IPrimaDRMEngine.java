package sune.app.mediadown.drm_engine.iprima;

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
import io.netty.handler.codec.http.HttpMethod;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media_engine.iprima.IPrimaAuthenticator;
import sune.app.mediadown.media_engine.iprima.IPrimaAuthenticator.SessionData;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.Reflection2;
import sune.app.mediadown.util.Reflection3;
import sune.app.mediadown.util.Web;
import sune.app.mediadownloader.drm.DRMBrowser;
import sune.app.mediadownloader.drm.DRMContext;
import sune.app.mediadownloader.drm.DRMEngine;
import sune.app.mediadownloader.drm.DRMResolver;
import sune.app.mediadownloader.drm.resolver.SimpleDRMResolver;
import sune.app.mediadownloader.drm.util.JS;
import sune.app.mediadownloader.drm.util.MPDQualityModifier;
import sune.util.ssdf2.SSDCollection;

public class IPrimaDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	IPrimaDRMEngine() {
	}
	
	private static final SessionData doHeadlessLogin() throws Exception {
		return IPrimaAuthenticator.getSessionData();
	}
	
	private static final CookieManager ensureCookieManager() throws Exception {
		Reflection3.invokeStatic(Web.class, "ensureCookieManager");
		return (CookieManager) Reflection2.getField(Web.class, null, "COOKIE_MANAGER");
	}
	
	private static final List<HttpCookie> savedCookies(URI uri) throws Exception {
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
		CefCookieManager cookieManager = CefCookieManager.getGlobalManager();
		Instant instant = Instant.now();
		Date now = Date.from(instant);
		Date expires = Date.from(instant.plus(7, ChronoUnit.DAYS));
		String uuid = null;
		
		for(HttpCookie cookie : cookies) {
			CefCookie cefCookie = new CefCookie(cookie.getName(), cookie.getValue(), cookie.getDomain(),
				cookie.getPath(), cookie.getSecure(), cookie.isHttpOnly(), now, now, false, expires);
			cookieManager.setCookie(url, cefCookie);
			
			if(cookie.getName().equals("prima_uuid")) {
				uuid = cookie.getValue();
			}
		}
		
		if(uuid != null) {
			CefCookie cefCookie = new CefCookie("prima_sso_logged_in", uuid, ".iprima.cz",
				"/", true, false, now, now, false, expires);
			cookieManager.setCookie(url, cefCookie);
		}
	}
	
	private static final void setCefLocalStorage(CefFrame frame, String name, String value) {
		JS.execute(frame, "window.localStorage.setItem('" + name + "', '" + value + "');");
	}
	
	@Override
	public DRMResolver createResolver(DRMContext context, String url, Path output, Media media) {
		return new IPrimaDRMResolver(context, url, output, media);
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
				|| !hostParts[1].equalsIgnoreCase("iprima.cz"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	private static final class IPrimaDRMResolver extends SimpleDRMResolver {
		
		private boolean isLoggedIn;
		private List<HttpCookie> cookies;
		private String rawString;
		
		public IPrimaDRMResolver(DRMContext context, String url, Path output, Media media) {
			super(context, url, output, media);
		}
		
		private static final boolean isAPIPlayURL(String url) {
			return url.startsWith("https://api.play-backend.iprima.cz/api/v1") && url.endsWith("/play");
		}
		
		@Override
		public void onLoadStart(DRMBrowser browser, CefFrame frame) {
			if(frame.getURL().startsWith(url)) {
				// Set consent cookie to not show the cookie consent popup.
				CefCookieManager cookieManager = CefCookieManager.getGlobalManager();
				Instant instant = Instant.now();
				Date now = Date.from(instant);
				Date expires = Date.from(instant.plus(7, ChronoUnit.DAYS));
				
				// This cookie value means "Decline all unnecessary cookies".
				String value = "CPm91oAPm91oAAHABBENC0CgAAAAAAAAAATIAAAAAAEkoAMAAQSbDQAYAA"
						+ "gk2KgAwABBJspABgACCTY6ADAAEEmyEAGAAIJNhIAMAAQSbGQAYAAgk2IgAwABBJsA"
						+ ".YAAAAAAAAAAA";
				
				for(String name : List.of("euconsent-v2", "eupubconsent-v2")) {
					cookieManager.setCookie(url, new CefCookie(name, value, ".iprima.cz", "/",
						false, false, now, now, false, expires));
				}
				
				// Hide the welcome banner on the bottom left
				setCefLocalStorage(frame, "onboarding_snackbar_hide", "1");
				
				JS.Helper.include(frame);
				JS.Helper.hideVideoElementStyle(frame);
				JS.Record.include(frame);
				JS.Record.activate(frame, "#prima-player > video");
			} else if(frame.getURL().startsWith("https://auth.iprima.cz")) {
				// For the system to think we are logged in, we must also put some information
				// to the localStorage, otherwise we would be logged out.
				setCefLocalStorage(frame, "prima_sso_token", rawString);
			}
		}
		
		@Override
		public void onLoadEnd(DRMBrowser browser, CefFrame frame, int httpStatusCode) {
			if(frame.getURL().startsWith(url)) {
				// "Disable" the autoplay next feature. This will actually just move
				// the threshold when the autoplay is triggered way out of the range
				// of the actual video duration, hopefully.
				JS.execute(frame, ""
					+ "document.querySelector('#popup-autoplay-next')"
					+ "    .setAttribute('data-current-timeline-treshold', 100 * 24 * 60 * 60)" // 100 days
				);
			}
		}
		
		@Override
		public boolean shouldModifyRequest(FullHttpRequest request) {
			return request.getUri().endsWith("/manifest.mpd") || isAPIPlayURL(request.getUri());
		}
		
		@Override
		public void modifyRequest(FullHttpRequest request) {
			// Disable encoding for specific requests
			request.headers().set("Accept-Encoding", "identity");
		}
		
		@Override
		public boolean shouldModifyResponse(String uri, String mimeType, Charset charset, FullHttpRequest request) {
			return request.getMethod() == HttpMethod.GET
						&& (mimeType.equalsIgnoreCase("application/dash+xml") || isAPIPlayURL(uri));
		}
		
		@Override
		public String modifyResponse(String uri, String mimeType, Charset charset, String content,
				FullHttpRequest request) {
			if(mimeType.equalsIgnoreCase("application/dash+xml")) {
				// Select the quality we want
				MPDQualityModifier modifier = MPDQualityModifier.fromString(content);
				modifier.modify(media.quality());
				content = modifier.xml().html();
			} else if(isAPIPlayURL(uri)) {
				SSDCollection json = JSON.read(content);
				
				// Remove ads before, after and throughout the video playback
				json.set("adsEnabled", false);
				json.set("videoAdsPreRoll", 0);
				json.set("videoAdsMidRoll", 0);
				json.set("videoAdsPostRoll", 0);
				json.set("videoAdsMidRollPositions", SSDCollection.emptyArray());
				json.set("videoAdsOverlayPositions", SSDCollection.emptyArray());
				json.set("videoAdsPreRollKeys", SSDCollection.emptyArray());
				json.set("videoAdsMidRollKeys", SSDCollection.emptyArray());
				json.set("videoAdsPostRollKeys", SSDCollection.emptyArray());
				json.set("videoAdsPreRollDelay", 0);
				
				content = json.toJSON();
			}
			
			return content;
		}
		
		@Override
		public String url() {
			if(!isLoggedIn) {
				try {
					SessionData sessionData = doHeadlessLogin();
					rawString = sessionData.rawString();
					cookies = savedCookies(Net.uri(url));
					setCefCookies(url, cookies);
					isLoggedIn = true;
				} catch(Exception ex) {
					throw new IllegalStateException("Unable to obtain the video iframe", ex);
				}
			}
			
			return url;
		}
	}
}