package sune.app.mediadown.drm_engine.iprima;

import java.net.URI;

import sune.app.mediadown.drm.DRMEngine;
import sune.app.mediadown.drm.DRMResolver;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web.Request;

public class IPrimaDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	IPrimaDRMEngine() {
	}
	
	@Override
	public DRMResolver createResolver() {
		return new IPrimaDRMResolver();
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
				|| !hostParts[1].equalsIgnoreCase("iprima.cz"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	private static final class IPrimaDRMResolver implements DRMResolver {
		
		private static final URI LICENSE_URI;
		
		static {
			LICENSE_URI = Net.uri("https://drm-widevine-licensing.axprod.net/AcquireLicense");
		}
		
		@Override
		public Request createRequest(Media media, byte[] licenseRequest) {
			String token = Media.root(media).metadata().get("drmToken", "");
			
			return Request.of(LICENSE_URI)
				.addHeaders("Referer", "https://www.iprima.cz/", "X-AxDRM-Message", token)
				.POST(licenseRequest, "application/octet-stream");
		}
	}
}