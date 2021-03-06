package sune.app.mediadown.server.youtube;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javafx.scene.image.Image;
import sune.app.mediadown.Shared;
import sune.app.mediadown.download.segment.FileSegment;
import sune.app.mediadown.download.segment.FileSegmentsHolder;
import sune.app.mediadown.download.segment.RemoteFileSegment;
import sune.app.mediadown.download.segment.RemoteFileSegmentsHolder;
import sune.app.mediadown.media.AudioMedia;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaMimeType;
import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.media.MediaQuality.AudioQualityValue;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.VideoMedia;
import sune.app.mediadown.media.VideoMediaContainer;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.server.Server;
import sune.app.mediadown.util.CheckedBiFunction;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Pair;
import sune.app.mediadown.util.Tuple;
import sune.app.mediadown.util.UserAgent;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Web.HeadRequest;
import sune.app.mediadown.util.Web.StringResponse;
import sune.app.mediadown.util.WorkerProxy;
import sune.app.mediadown.util.WorkerUpdatableTask;
import sune.util.ssdf2.SSDCollection;

public class YouTubeServer implements Server {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	private static Pattern REGEX_EMBED_URL;
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	// Allow to create an instance when registering the server
	YouTubeServer() {
	}
	
	private static final String maybeTransformEmbedURL(String url) {
		if(REGEX_EMBED_URL == null) {
			REGEX_EMBED_URL = Pattern.compile("^https?://(?:www\\.|m\\.)?youtube\\.com/embed/([^?#]+)(?:[?#].*)?$");
		}
		Matcher matcher = REGEX_EMBED_URL.matcher(url);
		return matcher.matches()
					? "https://www.youtube.com/watch?v=" + matcher.group(1)
					: url;
	}
	
	private final List<Media> getMedia(URI uri, Map<String, Object> data, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
		List<Media> sources = new ArrayList<>();
		String url = maybeTransformEmbedURL(uri.toString());
		StringResponse response = Web.request(new GetRequest(Utils.url(url), Shared.USER_AGENT));
		Document document = Utils.parseDocument(response.content, url);
		Signature.Context ctx = Signature.Extractor.extract(document);
		Elements scripts = document.getElementsByTag("script");
		Pattern patternConfigVariable = Pattern.compile("var ytInitialPlayerResponse\\s+=\\s+\\{");
		for(Element script : scripts) {
			String scriptHTML = script.html();
			Matcher matcher = patternConfigVariable.matcher(scriptHTML);
			if(matcher.find()) {
				String playerConfig = Utils.bracketSubstring(scriptHTML, '{', '}', false, matcher.end() - 1, scriptHTML.length());
				SSDCollection dataConfig = JSON.read(playerConfig);
				String title = dataConfig.getString("videoDetails.title");
				SSDCollection formatsConfig = dataConfig.getCollection("streamingData.adaptiveFormats");
				List<Tuple> videos = new ArrayList<>();
				List<Tuple> audios = new ArrayList<>();
				MediaFormat objFormat; MediaQuality objQuality; List<Tuple> list;
				// Parse the formats and obtains information about them
				for(SSDCollection format : formatsConfig.collectionsIterable()) {
					MediaMimeType mimeType = MediaMimeType.fromString(format.getDirectString("mimeType"));
					String type = mimeType.type();
					String typeAndSubtype = mimeType.typeAndSubtype();
					double duration = Double.valueOf(format.getDirectString("approxDurationMs", "0.0")) / 1000.0;
					if(type.startsWith("audio")) {
						// Parse the audio's format
						MediaFormat audioFormat = MediaFormat.fromMimeType(typeAndSubtype);
						objFormat = audioFormat;
						// Parse the audio's quality
						String qualityLabel = format.getDirectString("audioQuality");
						qualityLabel = qualityLabel.replace("AUDIO_QUALITY_", "");
						objQuality = MediaQuality.fromString(qualityLabel, MediaType.AUDIO);
						int bitRate = format.getDirectInt("bitrate", 0);
						int sampleRate = format.getDirectInt("audioSampleRate", 0);
						objQuality = objQuality.withValue(new MediaQuality.AudioQualityValue(0, sampleRate, bitRate, true));
						// Set to which list this item should be added
						list = audios;
					} else {
						// Parse the video's format
						MediaFormat videoFormat = MediaFormat.fromMimeType(typeAndSubtype);
						objFormat = videoFormat;
						// Parse the video's quality
						String qualityLabel = format.getDirectString("qualityLabel");
						objQuality = MediaQuality.fromString(qualityLabel, MediaType.VIDEO);
						// Set to which list this item should be added
						list = videos;
					}
					// Obtain the video/audio's URL
					String videoURL = null;
					// Video's URL does not need to be signed
					if(format.has("url")) {
						videoURL = format.getDirectString("url");
					}
					// Video's URL must be signed
					else {
						String cipher = format.getDirectString("cipher", null);
						if(cipher == null)
							cipher = format.getDirectString("signatureCipher", null);
						// Cannot get the cipher, just skip the source
						if(cipher == null)
							continue;
						videoURL = YT.decipher(cipher, ctx);
					}
					
					// Since YouTube introduced throttling, just check if it is not needed to
					// decipher rate bypass-related signature.
					videoURL = YT.maybeDecipherRateBypass(videoURL, document);
					
					// Obtain the video/audio's size
					long size = Long.valueOf(format.getDirectString("contentLength", "-1"));
					// Add the tuple with all information to the respective list
					list.add(new Tuple(videoURL, objFormat, objQuality, size, mimeType.codecs(), duration));
				}
				MediaSource source = MediaSource.of(this);
				MediaMetadata metadata = MediaMetadata.builder().title(title).build();
				// Combine the video and audio sources into final video sources
				for(Tuple videoData : videos) {
					String urlVideo = videoData.get(0);
					MediaFormat videoFormat = videoData.get(1);
					MediaQuality videoQuality = videoData.get(2);
					long videoSize = videoData.get(3);
					List<String> videoCodecs = videoData.get(4);
					double videoDuration = videoData.get(5);
					for(Tuple audioData : audios) {
						String urlAudio = audioData.get(0);
						MediaFormat audioFormat = audioData.get(1);
						MediaQuality audioQuality = audioData.get(2);
						long audioSize = audioData.get(3);
						List<String> audioCodecs = audioData.get(4);
						double audioDuration = audioData.get(5);
						AudioQualityValue aqv = (AudioQualityValue) audioQuality.value();
						
						// When YouTube provides data for playback it first sends a burst of data
						// and then slowly sends all the other data, i.e. the sending is throttled.
						// We can bypass it using preemptive segmentation of the whole file.
						FileSegmentsHolder<? extends FileSegment> segmentsVideo = Segmenter.buildSegments(urlVideo);
						FileSegmentsHolder<? extends FileSegment> segmentsAudio = Segmenter.buildSegments(urlAudio);
						
						Media media = VideoMediaContainer.separated().media(
							VideoMedia.segmented().source(source)
								.uri(Utils.uri(urlVideo)).format(videoFormat)
								.quality(videoQuality).metadata(metadata)
								.segments(segmentsVideo).size(videoSize)
								.codecs(videoCodecs).duration(videoDuration),
							AudioMedia.segmented().source(source)
								.uri(Utils.uri(urlAudio)).format(audioFormat)
								.quality(audioQuality).metadata(metadata)
								.segments(segmentsAudio).size(audioSize)
								.codecs(audioCodecs).duration(audioDuration)
								.bandwidth(aqv.bandwidth()).sampleRate(aqv.sampleRate())
						).build();
						
						sources.add(media);
						if(!function.apply(proxy, media))
							return null; // Do not continue
					}
				}
			}
		}
		return sources;
	}
	
	@Override
	public List<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return getMedia(uri, data, _dwp, (p, a) -> true);
	}
	
	@Override
	public WorkerUpdatableTask<CheckedBiFunction<WorkerProxy, Media, Boolean>, Void> getMedia
			(URI uri, Map<String, Object> data,
			 CheckedBiFunction<WorkerProxy, Media, Boolean> function) {
		return WorkerUpdatableTask.voidTaskChecked(function, (p, c) -> getMedia(uri, data, p, c));
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
		if(host.startsWith("www.")) // www prefix
			host = host.substring(4);
		if(host.startsWith("m."))   // mobile
			host = host.substring(2);
		if(!host.equals("youtube.com") &&
		   !host.equals("youtu.be"))
			return false;
		// Otherwise, it is probably compatible URL
		return true;
	}
	
	@Override
	public String title() {
		return TITLE;
	}
	
	@Override
	public String url() {
		return URL;
	}
	
	@Override
	public String version() {
		return VERSION;
	}
	
	@Override
	public String author() {
		return AUTHOR;
	}
	
	@Override
	public Image icon() {
		return ICON;
	}
	
	@Override
	public String toString() {
		return TITLE;
	}
	
	private static final class Segmenter {
		
		// Why this number? See: https://tyrrrz.me/blog/reverse-engineering-youtube
		private static final long SEGMENT_SIZE = 10L * 1024L * 1024L; // 10 MiB
		
		public static final FileSegmentsHolder<? extends FileSegment> buildSegments(String url) throws Exception {
			Map<String, String> args = Utils.urlParams(url);
			
			long clen = Optional.ofNullable(args.get("clen"))
					.map(Long::valueOf)
					.orElse(MediaConstants.UNKNOWN_SIZE);
			
			if(clen == MediaConstants.UNKNOWN_SIZE) {
				clen = Web.size(new HeadRequest(Utils.url(url), UserAgent.CHROME));
			}
			
			List<RemoteFileSegment> segments = new ArrayList<>();
			for(long start = 0L, end; start < clen; start += SEGMENT_SIZE) {
				end = Math.min(start + SEGMENT_SIZE, clen) - 1L; // end is inclusive, therefore N - 1
				URI uri = Utils.uri(url + "&range=" + start + "-" + end);
				segments.add(new RemoteFileSegment(uri, end - start + 1L));
			}
			
			double duration = Optional.ofNullable(args.get("dur"))
					.map(Double::valueOf)
					.orElse(MediaConstants.UNKNOWN_DURATION);
			
			return new RemoteFileSegmentsHolder(segments, duration);
		}
	}
	
	// Some parts taken from: https://youtube.com/s/player/5253ac4d/player_ias.vflset/cs_CZ/base.js
	// Update: 2020-07-18
	private static final class YT {
		
		private static Map<String, String> cacheRateBypass;
		
		private static final <T> T getOrDefault(T[] a, int i, T d) {
			return a != null && i >= 0 && i < a.length ? a[i] : d;
		}
		
		private static final boolean isArray(Object a) {
			// In this implementation this is effectively same as the YT one
			return a instanceof List;
		}
		
		private static final List<String> asArray(Object... a) {
			// This list is modifiable, not as the one returned by Arrays.asList
			List<String> l = new ArrayList<>(a.length);
			for(int i = 0, k = a.length; i < k; ++k)
				l.add((String) a[i]);
			return l;
		}
		
		private static final int length(Object a) {
			return a instanceof List ? ((List<?>) a).size() : 0;
		}
		
	    /*
	    Yp = function(a) {
			"?" == a.charAt(0) && (a = a.substr(1));
			return Wp(a)
	    };
	    */
		private static final Map<String, String> Yp(String a) {
			return Wp(a.charAt(0) == '?' ? a.substring(1) : a).entrySet().stream()
						.collect(Collectors.toMap((e) -> e.getKey(), (e) -> (String) e.getValue()));
		}
		
	    /*
	    Wp = function(a) {
			a = a.split("&");
			for (var b = {}, c = 0, d = a.length; c < d; c++) {
				var e = a[c].split("=");
				if (1 == e.length && e[0] || 2 == e.length) try {
					var f = gd(e[0] || ""),
						h = gd(e[1] || "");
					f in b ? Array.isArray(b[f]) ? nb(b[f], h) : b[f] = [b[f], h] : b[f] = h
				} catch (m) {
					if ("q" != e[0]) {
						var l = Error("Error decoding URL component");
						l.params = {
							key: e[0],
							value: e[1]
						};
						g.L(l)
					}
				}
			}
			return b
	    };
	    */
		@SuppressWarnings("unchecked")
		private static final Map<String, Object> Wp(String a) {
			Map<String, Object> b = new HashMap<>();
			String[] w = a.split("&");
			for(int c = 0, d = w.length; c < d; ++c) {
				String[] e = w[c].split("=");
				if(e.length == 1 && !e[0].isEmpty() || e.length == 2) {
					String f = gd(getOrDefault(e, 0, ""));
					String h = gd(getOrDefault(e, 1, ""));
					if(b.containsKey(f)) {
						if(isArray(b.get(f))) {
							nb((List<String>) b.get(f), h);
						} else {
							b.put(f, asArray(b.get(f), h));
						}
					} else {
						b.put(f, h);
					}
				}
			}
			return b;
		}
		
	    /*
	    gd = function(a) {
			return decodeURIComponent(a.replace(/\+/g, " "))
	    };
	    */
		private static final String gd(String a) {
			return JavaScript.decodeURIComponent(a.replaceAll("\\+", " "));
		}
		
	    /*
	    nb = function(a, b) {
			for (var c = 1; c < arguments.length; c++) {
				var d = arguments[c];
				if (g.La(d)) {
					var e = a.length || 0,
						f = d.length || 0;
					a.length = e + f;
					for (var h = 0; h < f; h++) a[e + h] = d[h]
				} else a.push(d)
			}
	    };
	    */
		@SuppressWarnings("unchecked")
		private static final void nb(List<String> a, Object... b) {
			for(int c = 0, l = b.length; c < l; ++c) {
				Object d = b[c];
				if(length(d) > 0) {
					a.addAll((List<String>) d);
				} else {
					a.add((String) d);
				}
			}
		}
		
		public static final String decipher(String cipher, Signature.Context ctx) throws Exception {
			Map<String, String> args = YT.Yp(cipher);
			return args.get("url") + '&' + args.get("sp") + '=' + ctx.alter(args.get("s"));
		}
		
		public static final String maybeDecipherRateBypass(String videoURL, Document document) throws Exception {
			Map<String, String> urlArgs = Utils.urlParams(videoURL);
			
			// Only process URLs that do not have ratebypass=yes in their query arguments
			if(!urlArgs.getOrDefault("ratebypass", "").equals("yes")) {
				String n = urlArgs.get("n");
				
				// The 'n' argument may not be present in the URL, so check for it
				if(n != null) {
					if(cacheRateBypass == null)
						cacheRateBypass = new HashMap<>();
					
					String deciphered;
					if((deciphered = cacheRateBypass.get(n)) == null) {
						deciphered = SignatureRateBypass.Extractor.extract(document).alter(n);
						cacheRateBypass.put(n, deciphered);
					}
					
					urlArgs.put("n", deciphered);
					String urlBase = videoURL.substring(0, videoURL.indexOf('?'));
					videoURL = urlBase + '?' + Utils.joinURLParams(urlArgs);
				}
			}
			
			return videoURL;
		}
	}
	
	private static final class SignatureUtils {
		
		private static String baseJSContent;
		
		private static final String obtainBaseJSContent(Document document) throws Exception {
			Element script;
			return (script = document.selectFirst("script[src*=\"player_ias\"]")) != null
						? Web.request(new GetRequest(Utils.url("https://youtube.com" + script.attr("src")),
						                             UserAgent.CHROME))
						     .content
						: null;
		}
		
		public static final String baseJSContent(Document document) throws Exception {
			return baseJSContent == null ? (baseJSContent = obtainBaseJSContent(document)) : baseJSContent;
		}
	}
	
	private static final class Signature {
		
		/*
		 * YouTube's signature handling is dynamic, i.e. the number of function calls
		 * and their arguments can be different for two videos. The functions remain
		 * the same but are present with slightly different names. Therefore we MUST
		 * extract the information about the function calls from linked base.js file
		 * at the video's page.
		 */
		
		protected static final class Context {
			
			private final Pair<CheckedBiFunction<char[], Integer, char[]>, Integer>[] theCalls;
			
			protected Context(Pair<CheckedBiFunction<char[], Integer, char[]>, Integer>[] calls) {
				theCalls = Objects.requireNonNull(calls);
			}
			
			public final String alter(String signature) throws Exception {
				char[] b = signature.toCharArray();
				for(Pair<CheckedBiFunction<char[], Integer, char[]>, Integer> call : theCalls)
					b = call.a.apply(b, call.b);
				return new String(b);
			}
		}
		
		/*
	    var Uu = {
			TX: function(a) {
				a.reverse()
			},
			Y0: function(a, b) {
				var c = a[0];
				a[0] = a[b % a.length];
				a[b % a.length] = c
			},
			X3: function(a, b) {
				a.splice(0, b)
			}
	    };
	    */
		protected static final class Functions {
			
			// Array reverse, taken from Apache Commons' ArraysUtils::reverse method:
			// https://github.com/apache/commons-lang/blob/master/src/main/java/org/apache/commons/lang3/ArrayUtils.java
			public static final char[] reverse(char[] a, int b) {
				int i = 0;
		        int j = a.length - 1;
		        char tmp;
		        while(j > i) {
		            tmp = a[j];
		            a[j] = a[i];
		            a[i] = tmp;
		            --j;
		            ++i;
		        }
		        return a;
			}
			
			public static final char[] swap(char[] a, int b) {
				char c = a[0];
				int  i = b % a.length;
				a[0] = a[i];
				a[i] = c;
				return a;
			}
			
			public static final char[] splice(char[] a, int b) {
				return Arrays.copyOfRange(a, b, a.length);
			}
		}
		
		protected static final class Extractor {
			
			private static final String PATTERN_STRING_FIND = "a\\.split\\(\"\"\\);(%s\\.[^\\}]+);return a\\.join\\(\"\"\\)";
			private static final String PATTERN_STRING_FMAP = "%s\\.([^\\(]+)\\(a,(\\d+)\\)";
			
			private static final CheckedBiFunction<char[], Integer, char[]> getSignatureFunction(String content) {
				if(content.contains("a.reverse"))
					return Functions::reverse;
				if(content.contains("a.splice"))
					return Functions::splice;
				if(content.contains("%a.length"))
					return Functions::swap;
				// No known function found
				return null;
			}
			
			private static final Pair<String, Map<String, CheckedBiFunction<char[], Integer, char[]>>> extractJSFunctionsMapping(String script) {
				String objectName = null;
				Map<String, CheckedBiFunction<char[], Integer, char[]>> mapping = new HashMap<>();
				// There is always only one instance of this call
				int index = script.indexOf("a.reverse()");
				if(index > 0) {
					// Get the object's content
					index = script.indexOf("}}", index);
					String content = Utils.bracketSubstring(script, '{', '}', true, index + 2, 0);
					content = content.substring(1, content.length() - 1);
					// Get the object's name
					int kvar = script.lastIndexOf("var ", index - (content.length() + 2));
					int sign = script.indexOf("=", kvar + 4);
					objectName = script.substring(kvar + 4, sign);
					// Parse the object's content
					for(String line : content.split("\\n")) {
						int pos = line.indexOf(':');
						if(pos > 0) {
							mapping.put(line.substring(0, pos), getSignatureFunction(line.substring(pos + 1)));
						}
					}
				}
				return new Pair<>(objectName, mapping);
			}
			
			private static final Pair<CheckedBiFunction<char[], Integer, char[]>, Integer>[] getFunctionCalls(String script,
					Map<String, CheckedBiFunction<char[], Integer, char[]>> mapping, String objectName) {
				String quotedObjectName = Pattern.quote(objectName);
				Pattern findPattern = Pattern.compile(String.format(PATTERN_STRING_FIND, quotedObjectName));
				Pattern fmapPattern = Pattern.compile(String.format(PATTERN_STRING_FMAP, quotedObjectName));
				Matcher matcher = findPattern.matcher(script);
				if(matcher.find()) {
					// Parse the function content to function calls
					@SuppressWarnings("unchecked")
					Pair<CheckedBiFunction<char[], Integer, char[]>, Integer>[] calls
						= Stream.of(matcher.group(1).split(";"))
							.map((s) -> {
								Matcher m;
								return (m = fmapPattern.matcher(s)).find()
											? new Pair<>(mapping.get(m.group(1)), Integer.parseInt(m.group(2)))
											: null;
							})
							.filter((p) -> p.a != null)
							.toArray(Pair[]::new);
					// Content parsed, calls obtained, we're done
					return calls;
				}
				return null;
			}
			
			public static final Context extract(Document document) throws Exception {
				String script = SignatureUtils.baseJSContent(document);
				if(script == null)
					return null; // Fast-fail
				Pair<String, Map<String, CheckedBiFunction<char[], Integer, char[]>>> pair = extractJSFunctionsMapping(script);
				if(pair == null)
					return null; // Fast-fail
				return new Context(getFunctionCalls(script, pair.b, pair.a));
			}
		}
	}
	
	private static final class SignatureRateBypass {
		
		/* YouTube introduced throttling of video playback, i.e. download. The term
		 * used in the YouTube environment is ratebypass. If argument of the same name
		 * is not present in the URL or does not have value of 'yes', then the video
		 * playback will be throttled. To bypass (pun intended) this throttling
		 * we have to decipher the 'n' URL argument and after that replace its value
		 * in the URL by the deciphered one.
		 */
		
		protected static final class Context {
			
			private static boolean nashornDeprecationWarningDisabled;
			
			private final Map<String, String> cache = new HashMap<>();
			private final String functionCode;
			
			protected Context(String functionCode) {
				this.functionCode = Objects.requireNonNull(functionCode);
			}
			
			// Nashorn Scripting Engine that we use (in the JavaScript class) is deprecated in Java 11
			// and outputs a warning to stderr. This method ensures that the warning is hidden.
			private static final void ensureNashornDeprecationWarningIsDisabled() {
				if(nashornDeprecationWarningDisabled) return; // Already disabled
				String insert = "--no-deprecation-warning";
				String name = "nashorn.args", value = System.getProperty("nashorn.args", "");
				if(!value.contains(insert)) System.setProperty(name, value + ' ' + insert);
				nashornDeprecationWarningDisabled = true;
			}
			
			private final String execute(String signature) throws Exception {
				return (String) JavaScript.execute(functionCode + "('" + signature.replace("'", "\\'") + "')");
			}
			
			public final String alter(String signature) throws Exception {
				ensureNashornDeprecationWarningIsDisabled();
				
				String altered;
				if((altered = cache.get(signature)) != null)
					return altered;
				
				altered = execute(signature);
				cache.put(signature, altered);
				return altered;
			}
		}
		
		protected static final class Extractor {
			
			private static final Pattern REGEX_FUNCTION_NAME
				= Pattern.compile("a\\.get\\(\"n\"\\).*?b=(?<fnc>[^\\[\\(]+)(?:\\[(?<idx>\\d+)\\])?\\(b\\)");
			
			private static final String extractLookupFunctionName(String script, String lookupName, int index) {
				if(index < 0) return lookupName;
				int i = script.indexOf(lookupName + "=[");
				if(i < 0) return null;
				int start = i + lookupName.length() + 1;
				String array = Utils.bracketSubstring(script, '[', ']', false, start, script.length());
				array = array.substring(1, array.length() - 1); // Remove brackets
				return array.split(",")[index];
			}
			
			private static final String extractFunctionName(String script) {
				Matcher matcher = REGEX_FUNCTION_NAME.matcher(script);
				if(!matcher.find()) return null;
				String lookupName = matcher.group("fnc");
				String idx = Optional.ofNullable(matcher.group("idx")).orElse("");
				int index = !idx.isEmpty() ? Integer.parseInt(idx) : -1;
				return extractLookupFunctionName(script, lookupName, index);
			}
			
			public static final Context extract(Document document) throws Exception {
				String script = SignatureUtils.baseJSContent(document);
				if(script == null) return null;
				
				String functionName = extractFunctionName(script);
				if(functionName == null) return null;
				
				int i = script.indexOf(functionName + "=function");
				if(i < 0) return null;
				int start = i + functionName.length() + 9;
				String content = Utils.bracketSubstring(script, '{', '}', false, start, script.length());
				
				int fstart = i + functionName.length() + 1;
				int fend = script.indexOf('{', fstart);
				String declaration = script.substring(fstart, fend);
				
				return new Context("(" + declaration + content + ")");
			}
		}
	}
}