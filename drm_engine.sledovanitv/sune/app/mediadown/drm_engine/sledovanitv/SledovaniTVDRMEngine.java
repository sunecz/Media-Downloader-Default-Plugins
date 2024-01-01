package sune.app.mediadown.drm_engine.sledovanitv;

import java.net.URI;

import sune.app.mediadown.drm.DRMEngine;
import sune.app.mediadown.drm.DRMResolver;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.util.Utils;

public class SledovaniTVDRMEngine implements DRMEngine {
	
	// Allow to create an instance when registering the engine
	SledovaniTVDRMEngine() {
	}
	
	@Override
	public DRMResolver createResolver() {
		return new SledovaniTVDRMResolver();
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
		if(host.startsWith("www.")) // www prefix
			host = host.substring(4);
		if(!host.equals("sledovanitv.cz")
				&& !host.equals("sledovanietv.sk"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	private static final class SledovaniTVDRMResolver implements DRMResolver {
		
		private static final String LICENSE_URI_TEMPLATE;
		
		static {
			LICENSE_URI_TEMPLATE = "https://drm.srv.czcloud.i.mtvreg.com/license/prod/widevine"
					+ "?login=%{profile_id}s"
					+ "&password=ukyg3uh16g1394gp6wdd"
					+ "&device=%{device_id}s"
					+ "&streamURL=%{stream_url}s";
		}
		
		@Override
		public Request createRequest(Media media) {
			MediaMetadata metadata = media.metadata();
			String profileId = metadata.get("profileId", "");
			String deviceId = metadata.get("deviceId", "");
			String streamUrl = media.uri().toString();
			
			String referer = Utils.format(
				"https://%{host}s/",
				"host", media.metadata().sourceURI().getHost()
			);
			
			URI licenseUri = Net.uri(Utils.format(
				LICENSE_URI_TEMPLATE,
				"profile_id", profileId,
				"device_id", deviceId,
				"stream_url", Utils.base64URLEncode(streamUrl)
			));
			
			// The body will be replaced with content of the challenge
			return Request.of(licenseUri)
				.addHeaders("Referer", referer)
				.POST("", "application/octet-stream");
		}
	}
}