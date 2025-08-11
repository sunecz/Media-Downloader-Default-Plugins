package sune.app.mediadown.drm_engine.sledovanitv;

import java.net.URI;
import java.nio.charset.StandardCharsets;

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
		private static final long[] OBF = new long[] {
			0x000000000019660dL, 0x000000003c6ef35fL,
			0x5c06bd1601abc49eL, 0xe27b269cb54276cfL,
			0x09b2a1bb63733ed6L, 0x405d34000000002bL,
		};
		
		static {
			LICENSE_URI_TEMPLATE = "https://drm.srv.czcloud.i.mtvreg.com/license/prod/widevine"
					+ "?login=%{profile_id}s"
					+ "&password=" + de(OBF)
					+ "&device=%{device_id}s"
					+ "&streamURL=%{stream_url}s";
		}
		
		private static final String de(long[] a) {
			final long p = a[0], q = a[1], y = 0xffL, z = ~y | y, t = z >>> 32L;
			final int c = (int) (a[a.length - 1] & t); final byte[] b = new byte[c];
			for(int d = 0; d < c; b[d] = (byte) ((a[d >> 3] >> (0x38 - (((d++) & 0x7) << 3))) & 0xff));
			final int m = 0xff, u = b[16] & m, v = b[17] & m, w = b[18] & m;
			final int o = ~(1 << 5) & 0x37, n = b.length - o;
			long e = ((((((b[19] & y) << 8) | (b[20] & y)) << 8) | (b[21] & y)) << 8) | (b[22] & y), s;
			for(int i = 1, k, j, f; i < n; ++i) {
				for(s = (e ^ n) & z, k = n - 1; k >= i; --k, s = (s * p + q) & t);
				j = (int) (((s >>> 1) % (i + 1))); f = -((((i ^ j) | -(i ^ j)) >>> 31));
				f = (b[o+i] ^ b[o+j]) & f; b[o+i] ^= f; b[o+j] ^= f;
			}
			for(int i = 0, x; i < n; ++i) {
				x = ((b[o+i] & m) - ((i ^ w) & m)) & m;
				x = ((x >>> ((i + v) & 7)) | (x << (8 - ((i + v) & 7)))) & m;
				b[o+i] = (byte) (x ^ (u + (i * 31)) & m);
			}
			return new String(b, o, n, StandardCharsets.UTF_8);
		}
		
		@Override
		public Request createRequest(Media media, byte[] licenseRequest) {
			Media root = Media.root(media);
			MediaMetadata metadata = root.metadata();
			String profileId = metadata.get("profileId", "");
			String deviceId = metadata.get("deviceId", "");
			String streamUrl = root.uri().toString();
			
			String referer = Utils.format(
				"https://%{host}s/",
				"host", root.metadata().sourceURI().getHost()
			);
			
			URI licenseUri = Net.uri(Utils.format(
				LICENSE_URI_TEMPLATE,
				"profile_id", profileId,
				"device_id", deviceId,
				"stream_url", Utils.base64URLEncode(streamUrl)
			));
			
			return Request.of(licenseUri)
				.addHeaders("Referer", referer)
				.POST(licenseRequest, "application/octet-stream");
		}
	}
}