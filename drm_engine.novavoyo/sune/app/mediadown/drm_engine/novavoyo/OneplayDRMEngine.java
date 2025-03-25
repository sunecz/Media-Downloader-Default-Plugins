package sune.app.mediadown.drm_engine.novavoyo;

import java.net.URI;

import sune.app.mediadown.drm.DRMEngine;
import sune.app.mediadown.drm.DRMResolver;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web.Request;

public class OneplayDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	OneplayDRMEngine() {
	}
	
	@Override
	public DRMResolver createResolver() {
		return new OneplayDRMResolver();
	}
	
	@Override
	public boolean isCompatibleURI(URI uri) {
		String protocol = uri.getScheme();
		
		if(!protocol.equals("http") && !protocol.equals("https")) {
			return false;
		}
		
		String host = uri.getHost();
		
		if(host.startsWith("www.")) {
			host = host.substring(4);
		}
		
		if(!host.equals("oneplay.cz")) {
			return false;
		}
		
		return true;
	}
	
	private static final class OneplayDRMResolver implements DRMResolver {
		
		private static final URI LICENSE_URI;
		
		static {
			LICENSE_URI = Net.uri("https://drm-proxy-widevine.cms.jyxo-tls.cz/AcquireLicense");
		}
		
		@Override
		public Request createRequest(Media media, byte[] licenseRequest) {
			String token = Media.root(media).metadata().get("drmToken", "");
			
			return Request.of(LICENSE_URI)
				.addHeaders("Referer", "https://www.oneplay.cz/", "X-AxDRM-Message", token)
				.POST(licenseRequest, "application/octet-stream");
		}
	}
}
