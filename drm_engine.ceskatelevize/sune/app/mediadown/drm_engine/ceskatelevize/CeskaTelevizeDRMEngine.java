package sune.app.mediadown.drm_engine.ceskatelevize;

import java.net.URI;

import sune.app.mediadown.drm.DRMEngine;
import sune.app.mediadown.drm.DRMResolver;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web.Request;

public class CeskaTelevizeDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	CeskaTelevizeDRMEngine() {
	}
	
	@Override
	public DRMResolver createResolver() {
		return new CTDRMResolver();
	}
	
	@Override
	public boolean isCompatibleURI(URI uri) {
		// Check the protocol
		String protocol = uri.getScheme();
		if(!protocol.equals("http") &&
		   !protocol.equals("https"))
			return false;
		// Check the host
		String[] hostParts = uri.getHost().split("\\.", 2);
		if(hostParts.length < 2
				// Check only the second and top level domain names,
				// since there are many subdomains, and there may be
				// possibly even more in the future.
				|| !hostParts[1].equalsIgnoreCase("ceskatelevize.cz"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	private static final class CTDRMResolver implements DRMResolver {
		
		private static final URI LICENSE_URI;
		
		static {
			String accessToken = "c3RlcGFuLWEtb25kcmEtanNvdS1wcm9zdGUtbmVqbGVwc2k=";
			LICENSE_URI = Net.uri("https://ivys-wvproxy.o2tv.cz/license?access_token=" + accessToken);
		}
		
		@Override
		public Request createRequest(Media media) {
			// The body will be replaced with content of the challenge
			return Request.of(LICENSE_URI)
				.addHeaders("Referer", "https://player.ceskatelevize.cz/")
				.POST("", "application/octet-stream");
		}
	}
}