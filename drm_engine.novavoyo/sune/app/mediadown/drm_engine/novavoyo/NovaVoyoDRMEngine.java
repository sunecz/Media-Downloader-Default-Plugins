package sune.app.mediadown.drm_engine.novavoyo;

import java.net.URI;

import sune.app.mediadown.drm.DRMEngine;
import sune.app.mediadown.drm.DRMResolver;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web.Request;

public class NovaVoyoDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	NovaVoyoDRMEngine() {
	}
	
	@Override
	public DRMResolver createResolver() {
		return new NovaVoyoDRMResolver();
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
		if(!host.equals("voyo.nova.cz"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	private static final class NovaVoyoDRMResolver implements DRMResolver {
		
		private static final URI LICENSE_URI;
		
		static {
			LICENSE_URI = Net.uri("https://drm-widevine-licensing.axprod.net/AcquireLicense");
		}
		
		@Override
		public Request createRequest(Media media) {
			String token = media.metadata().get("drmToken", "");
			
			// The body will be replaced with content of the challenge
			return Request.of(LICENSE_URI)
				.addHeaders("Referer", "https://media.cms.nova.cz/", "X-AxDRM-Message", token)
				.POST("", "application/octet-stream");
		}
	}
}