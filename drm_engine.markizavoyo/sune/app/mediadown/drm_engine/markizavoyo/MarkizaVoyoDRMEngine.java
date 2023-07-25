package sune.app.mediadown.drm_engine.markizavoyo;

import java.net.URI;

import sune.app.mediadown.drm.DRMEngine;
import sune.app.mediadown.drm.DRMResolver;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web.Request;

public class MarkizaVoyoDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	MarkizaVoyoDRMEngine() {
	}
	
	@Override
	public DRMResolver createResolver() {
		return new MarkizaVoyoDRMResolver();
	}
	
	@Override
	public boolean isCompatibleURI(URI uri) {
		// Check the protocol
		String protocol = uri.getScheme();
		if(!protocol.equals("http") &&
		   !protocol.equals("https"))
			return false;
		// Check the host
		String host = uri.getHost();
		if((host.startsWith("www."))) // www prefix
			host = host.substring(4);
		if(!host.equals("voyo.markiza.sk"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	private static final class MarkizaVoyoDRMResolver implements DRMResolver {
		
		private static final URI LICENSE_URI;
		
		static {
			LICENSE_URI = Net.uri("https://drm-widevine-licensing.axprod.net/AcquireLicense");
		}
		
		@Override
		public Request createRequest(Media media) {
			String token = media.metadata().get("drmToken", "");
			
			// The body will be replaced with content of the challenge
			return Request.of(LICENSE_URI)
				.addHeaders("Referer", "https://media.cms.markiza.sk/", "X-AxDRM-Message", token)
				.POST("", "application/octet-stream");
		}
	}
}