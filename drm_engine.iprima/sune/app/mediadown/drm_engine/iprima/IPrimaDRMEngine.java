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
import sune.app.mediadown.util.JSON;
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
import sune.util.ssdf2.SSDCollection;

public class IPrimaDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	IPrimaDRMEngine() {
	}
	
	private static final Object doHeadlessLogin() throws Exception {
		Class<?> clazz = Class.forName("sune.app.mediadown.media_engine.iprima.IPrimaAuthenticator");
		return (Object) Reflection3.invokeStatic(clazz, "getSessionData");
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
	
	private static final void setCefLocalStorage(CefFrame frame, List<HttpCookie> cookies) {
		HttpCookie cookieUser = cookies.stream()
			.filter((c) -> c.getName().equals("prima_current_user"))
			.findFirst().get();
		SSDCollection tokenData = JSON.read(Utils.base64Decode(cookieUser.getValue()));
		String tokenString = tokenData.getCollection("token").toJSON(true);
		JS.execute(frame, "window.localStorage.setItem('prima_sso_token', '" + tokenString + "');");
	}
	
	@Override
	public DRMResolver createResolver(DRMContext context, String url, Path output, Media media) {
		return new IPrimaDRMResolver(context, url, output, media);
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
		if(!host.equals("iprima.cz"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	private static final class IPrimaDRMResolver extends SimpleDRMResolver {
		
		private boolean isLoggedIn;
		private List<HttpCookie> cookies;
		
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
				String value = "CPjDWYAPjDWYAAHABBENCrCgAAAAAAAAAATIAAAAAAEkoAMAAQSTDQAYAA"
						+ "gkmKgAwABBJMpABgACCSY6ADAAEEkyEAGAAIJJhIAMAAQSTGQAYAAgkmIgAwABBJMA"
						+ ".YAAAAAAAAAAA";
				
				for(String name : List.of("euconsent-v2", "eupubconsent-v2")) {
					cookieManager.setCookie(url, new CefCookie(name, value, ".iprima.cz", "/",
						false, false, now, now, false, expires));
				}
				
				JS.Helper.include(frame);
				JS.Helper.hideVideoElementStyle(frame);
				JS.Record.include(frame);
				JS.Record.activate(frame, ".video-js[id^='video-player-'] > video");
			} else if(frame.getURL().startsWith("https://auth.iprima.cz")) {
				// For the system to think we are logged in, we must also put some information
				// to the localStorage, otherwise we would be logged out.
				setCefLocalStorage(frame, cookies);
			}
		}
		
		@Override
		public void onLoadEnd(DRMBrowser browser, CefFrame frame, int httpStatusCode) {
			// Nothing to do
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
			return mimeType.equalsIgnoreCase("application/dash+xml")
						|| (request.getMethod() == HttpMethod.GET && isAPIPlayURL(uri));
		}
		
		@Override
		public String modifyResponse(String uri, String mimeType, Charset charset, String content,
				FullHttpRequest request) {
			if(mimeType.equalsIgnoreCase("application/dash+xml")) {
				// Select the quality we want
				MPDQualityModifier modifier = MPDQualityModifier.fromString(content);
				modifier.modify(media.quality());
				content = modifier.xml().html();
			} else if(request.getMethod() == HttpMethod.GET && isAPIPlayURL(uri)) {
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
					doHeadlessLogin();
					cookies = savedCookies(Utils.uri(url));
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