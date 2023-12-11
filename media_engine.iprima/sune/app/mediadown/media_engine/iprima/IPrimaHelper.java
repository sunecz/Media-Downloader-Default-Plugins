package sune.app.mediadown.media_engine.iprima;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpHeaders;
import java.nio.channels.ClosedByInterruptException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.zip.GZIPInputStream;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.concurrent.CounterLock;
import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media_engine.iprima.IPrimaEngine.IPrima;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.CheckedRunnable;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONType;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Reflection;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

final class IPrimaHelper {
	
	private static PluginBase PLUGIN;
	
	private static final Regex REGEX_EPISODE_NAME = Regex.of("^\\d+\\.[^\\-]+-\\s+(.*)$");
	private static final HttpHeaders HEADERS_GZIP = Web.Headers.ofSingle("Accept-Encoding", "gzip");
	
	private static final HttpHeaders ensureAcceptGzip(HttpHeaders headers) {
		boolean isGzip = headers
			.firstValue("Accept-Encoding")
			.filter("gzip"::equalsIgnoreCase)
			.isPresent();
		
		if(isGzip) {
			return headers; // No need to change
		}
		
		return Web.Headers.ofMap(Utils.mergeNew(headers.map(), HEADERS_GZIP.map()));
	}
	
	private static final String request(URI uri, HttpHeaders headers) throws Exception {
		try(Response.OfStream response = Web.requestStream(Request.of(uri).headers(ensureAcceptGzip(headers)).GET())) {
			InputStream stream = response.stream();
			boolean isGzip = response.headers()
				.firstValue("Content-Encoding")
				.filter("gzip"::equalsIgnoreCase)
				.isPresent();
			
			if(isGzip) {
				stream = new GZIPInputStream(stream);
			}
			
			return Utils.streamToString(stream);
		}
	}
	
	private static final String request(URI uri) throws Exception {
		return request(uri, Web.Headers.empty());
	}
	
	private static final HttpHeaders authenticationRequestHeaders() {
		// It is important to specify the referer, otherwise the response code is 403.
		Map<String, String> mutRequestHeaders = Utils.toMap("Referer", "https://www.iprima.cz/");
		
		try {
			// Try to log in to the iPrima website using the internal account to have HD sources available.
			PrimaAuthenticator.SessionData sessionData = PrimaAuthenticator.sessionData();
			Utils.merge(mutRequestHeaders, sessionData.requestHeaders());
		} catch(Exception ex) {
			// Notify the user that the HD sources may not be available due to inability to log in.
			PrimaCommon.error(ex);
		}
		
		return Web.Headers.ofSingleMap(mutRequestHeaders);
	}
	
	public static final int compareNatural(String a, String b) {
		// Delegate to the new more performant method for natural comparison
		return Utils.compareNaturalIgnoreCase(a, b);
	}
	
	public static final void setPlugin(PluginBase plugin) {
		PLUGIN = plugin;
	}
	
	public static final PluginConfiguration configuration() {
		return PLUGIN.getContext().getConfiguration();
	}
	
	public static final Translation translation() {
		String path = "plugin." + PLUGIN.getContext().getPlugin().instance().name();
		return MediaDownloader.translation().getTranslation(path);
	}
	
	public static final String credentialsName() {
		return "plugin/" + PLUGIN.getContext().getPlugin().instance().name().replace('.', '/');
	}
	
	private static abstract class DefaultMediaObtainerBase {
		
		protected DefaultMediaObtainerBase() {
		}
		
		protected abstract String playURL();
		
		public ListTask<Media> getMedia(URI uri, MediaEngine engine) throws Exception {
			return ListTask.of((task) -> {
				Document document = HTML.from(uri);
				String productId = null;
				
				// (1) Extract product ID from iframe's URL
				Element elIframe;
				if((elIframe = document.selectFirst("iframe.video-embed")) != null) {
					productId = Net.queryDestruct(elIframe.attr("src")).valueOf("id", null);
				}
				
				// (2) OR Extract product ID from JavaScript player configuration
				if(productId == null) {
					for(Element elScript : document.select("script[type='text/javascript']")) {
						String scriptContent = elScript.html();
						if(scriptContent.contains("productId")) {
							productId = Utils.unquote(JavaScript.varcontent(scriptContent, "productId"));
							break;
						}
					}
				}
				
				// (3) OR Extract product ID from JavaScript player configuration (videos variable)
				if(productId == null) {
					for(Element elScript : document.select("script:not([type='text/javascript'])")) {
						String scriptContent = elScript.html();
						if(scriptContent.contains("videos")) {
							productId = Utils.unquote(JavaScript.varcontent(scriptContent, "videos"));
							break;
						}
					}
				}
				
				if(productId == null) {
					return; // Do not continue
				}
				
				URL configURL = Net.url(Utils.format(playURL(), "product_id", productId));
				
				HttpHeaders requestHeaders = authenticationRequestHeaders();
				String content = request(Net.uri(configURL), requestHeaders);
				if(content == null || content.isEmpty()) {
					return; // Do not continue
				}
				
				JSONCollection data = JSON.read(content);
				// Try to obtain the full title of the media
				String title = "";
				Element elMediaInfo = document.selectFirst("script[type='application/ld+json']");
				
				if(elMediaInfo != null) {
					JSONCollection mediaInfo = JSON.read(elMediaInfo.html());
					String programName = mediaInfo.getString("partOfSeries.name", "");
					String seasonName = mediaInfo.getString("partOfSeason.name", "");
					String rawEpisode = mediaInfo.getString("episodeNumber", "0");
					String numEpisode = String.format("%02d", Integer.valueOf(rawEpisode));
					
					// If a video is not part of any series, there is only a name of the video
					if(programName.isEmpty()) {
						programName = mediaInfo.getString("name", "");
						String regexNumEpisode = "\\s*\\((\\d+)\\)$";
						
						// Extract the episode number, if it exists in the name
						if(rawEpisode.equals("0")) {
							Matcher matcher = Regex.of(regexNumEpisode).matcher(programName);
							
							if(matcher.find()) {
								numEpisode = String.format("%02d", Integer.valueOf(matcher.group(1)));
							}
						}
						
						// Remove the episode number, if it exists in the name
						programName = programName.replaceAll(regexNumEpisode, "");
					}
					
					// The season number is probably in roman numerals, we have to convert it to an integer
					String seasonRoman = Opt.of(seasonName.split(" "))
		                    .ifTrue((a) -> a.length > 1).map((a) -> a[1])
		                    .orElse("I");
					int intSeason = Utils.romanToInteger(seasonRoman);
					String numSeason = "";
					boolean splitSeasonAndEpisode = false;
					
					// The season can also be a string, in that case we have to return different title
					if(intSeason < 0) {
						numSeason = Utils.validateFileName(seasonName)
								.replace("Sezóna", "")
								.replaceAll("([\\p{L}0-9]+)\\s+", "$1")
								.replaceAll("[^[\\p{L}0-9]+]", "_")
								.replaceAll("(^_+|_+$)", "")
								.replaceAll("_([^_]+)(_|$)", "($1)")
								.replaceAll("\\s+", " ")
								.trim();
						
						// Check whether the last character is an alpha-numeric one
						if(!numSeason.isEmpty()
								&& numSeason.substring(numSeason.length() - 1).matches("\\p{L}$")) {
							// If so, the appended 'x' would cause a problem, so we have to split the season
							// number and the episode number.
							splitSeasonAndEpisode = true;
						}
					} else {
						// Season is a valid roman integer and was successfully converted
						numSeason = String.format("%02d", intSeason);
					}
					
					// The episode name is not present in the mediaInfo, so we have to get it from the document
					final String find = "videoData = {";
					String fullName = "";
					
					for(Element script : document.select("script[type='text/javascript']")) {
						String con = script.html();
						int index = con.indexOf(find);
						
						if(index > 0) {
							con = Utils.bracketSubstring(con, '{', '}', false, index + find.length() - 1, con.length());
							JSONCollection json = JavaScript.readObject(con);
							fullName = json.getString("title", "");
							
							if(!fullName.isEmpty()) {
								break;
							}
						}
					}
					
					// Finally, extract only the episode name from the full name
					String episodeName = "";
					Matcher matcher = REGEX_EPISODE_NAME.matcher(fullName);
					
					if(matcher.matches()) {
						episodeName = matcher.group(1);
						// The episode name can be in the format "[program_name] - [season] ([episode])",
						// which is not desirable.
						String text = programName + " " + seasonRoman + " (" + rawEpisode + ")";
						
						if(episodeName.equalsIgnoreCase(text)) {
							episodeName = "";
						}
					}
					
					title = MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName, splitSeasonAndEpisode);
				}
				
				// The outer collection may be an array, we have to flatten it first, if it is
				JSONCollection streamInfos;
				if(data.type() == JSONType.ARRAY) {
					streamInfos = JSONCollection.emptyArray();
					Utils.stream(data.collectionsIterator())
						.flatMap((c) -> Utils.stream(c.getCollection("streamInfos").collectionsIterator()))
						.forEach(streamInfos::add);
				} else {
					streamInfos = data.getCollection("streamInfos");
				}
				
				URI sourceURI = uri;
				MediaSource source = MediaSource.of(engine);
				
				for(JSONCollection streamInfo : streamInfos.collectionsIterable()) {
					String src = streamInfo.getString("url");
					MediaLanguage language = MediaLanguage.ofCode(streamInfo.getString("lang.key"));
					
					List<Media> media = MediaUtils.createMedia(
						source, Net.uri(src), sourceURI, title, language, MediaMetadata.empty()
					);
					
					for(Media s : media) {
						if(!task.add(s)) {
							return; // Do not continue
						}
					}
				}
			});
		}
	}
	
	static final class PrimaAPIProgramObtainer {
		
		// URLs
		private static final String URL_API = "https://prima.iprima.cz/iprima-api/ListWithFilter/%{type}s/Content"
			+ "?filter=all"
			+ "&channel_restriction=%{subdomain}s";
		
		// Constants
		private static final String TYPE_MOVIE = "Movies";
		private static final String TYPE_SHOW = "Series";
		private static final String GRAPHQL_TYPE_MOVIE = "VideoNode";
		private static final String GRAPHQL_TYPE_SHOW = "ProgramNode";
		
		// Other
		private static PrimaAPIProgramObtainer INSTANCE;
		
		private PrimaAPIProgramObtainer() {
		}
		
		public static final PrimaAPIProgramObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new PrimaAPIProgramObtainer()) : INSTANCE;
		}
		
		private static final void getProgramsOfType(ListTask<Program> task, IPrima iprima, String subdomain,
				String type, String graphQLType) throws Exception {
			URI apiUri = Net.uri(Utils.format(URL_API, "subdomain", subdomain, "type", type));
			String response = request(apiUri);
			
			if(response == null) {
				return; // Do not continue
			}
			
			JSONCollection json = JSON.read(response);
			String content = json.getString("content");
			Document document = HTML.parse(content);
			Elements programs = document.select(".component--scope--cinematography > a");
			
			for(Element elProgram : programs) {
				// Ignore non-free programs
				if(elProgram.selectFirst(".component--scope--cinematography--picture--bottom--price") != null) {
					continue;
				}
				
				URI url = Net.uri(Net.uriFix(elProgram.attr("href")));
				String title = elProgram.attr("title");
				JSONCollection data = JSON.read(elProgram.attr("data-item-json"));
				String id = String.valueOf(data.getInt("id"));
				Program program = new Program(url, title, "source", iprima, "id", id, "type", graphQLType);
				
				if(!task.add(program)) {
					return; // Do not continue
				}
			}
		}
		
		public ListTask<Program> getPrograms(IPrima iprima, String subdomain) throws Exception {
			return ListTask.of((task) -> {
				getProgramsOfType(task, iprima, subdomain, TYPE_SHOW, GRAPHQL_TYPE_SHOW);
				getProgramsOfType(task, iprima, subdomain, TYPE_MOVIE, GRAPHQL_TYPE_MOVIE);
			});
		}
	}
	
	static final class StaticProgramObtainer {
		
		// Selectors
		private static final String SELECTOR_PROGRAM = ".programmes-list .w-full h2 > a";
		
		// Other
		private static StaticProgramObtainer INSTANCE;
		
		private StaticProgramObtainer() {
		}
		
		public static final StaticProgramObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new StaticProgramObtainer()) : INSTANCE;
		}
		
		public ListTask<Program> getPrograms(IPrima iprima, String urlPrograms) throws Exception {
			return ListTask.of((task) -> {
				URI uriPrograms = Net.uri(urlPrograms);
				String response = request(uriPrograms);
				Document document = HTML.parse(response, uriPrograms);
				
				for(Element elProgram : document.select(SELECTOR_PROGRAM)) {
					String url = elProgram.absUrl("href");
					String title = elProgram.text();
					Program program = new Program(Net.uri(url), title, "source", iprima, "type", "ProgramNode");
					
					if(!task.add(program)) {
						break; // Do not continue
					}
				}
			});
		}
	}
	
	static final class ThemedContentEpisodeObtainer {
		
		// General
		private static final String URL_API_IPRIMA
			= "https://%{subdomain}s.iprima.cz/iprima-api/View_Entity_Node_Program/ThemedContent/More"
					+ "?id=%{id}s"
					+ "&offset=%{offset}d"
					+ "&subpage_type=%{type}s";
		
		// Selectors
		private static final String	SELECTOR_EPISODE = ".program";
		
		// Other
		private static ThemedContentEpisodeObtainer INSTANCE;
		
		private ThemedContentEpisodeObtainer() {
		}
		
		public static final ThemedContentEpisodeObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new ThemedContentEpisodeObtainer()) : INSTANCE;
		}
		
		private final Episode parseEpisodeElement(Element elEpisode, Program program) {
			Element elLink = elEpisode.selectFirst("a");
			
			URI uri = Net.uri(Net.uriFix(elLink.attr("href")));
			String title = elLink.attr("title");
			
			return new Episode(program, uri, title);
		}
		
		public ListTask<Episode> getEpisodes(Program program, String subdomain) throws Exception {
			return ListTask.of((task) -> {
				String id = program.get("id");
				
				if(id == null || id.isEmpty()) {
					return; // Cannot do anything without an ID
				}
				
				// Check for movies, they do not have any episodes
				if(program.get("type").equals("VideoNode")) {
					// Nothing to do, just set the program information
					Episode episode = new Episode(program, program.uri(), program.title());
					
					if(!task.add(episode)) {
						return; // Do not continue
					}
				} else {
					String callUriTemplate = Utils.format(
						URL_API_IPRIMA,
						"subdomain", subdomain,
						"id", id,
						"type", "videos" // Currently only videos are available
					);
					
					// Loop through all available offsets with non-empty content
					for(int offset = 0;;) {
						URI callUri = Net.uri(Utils.format(callUriTemplate, "offset", offset));
						JSONCollection response = JSON.read(request(callUri));
						String content = response.getString("related_content");
						Document document = HTML.parse(content);
						
						for(Element elEpisode : document.select(SELECTOR_EPISODE)) {
							Episode episode = parseEpisodeElement(elEpisode, program);
							
							if(!task.add(episode)) {
								return; // Do not continue
							}
						}
						
						if(response.getBoolean("hide_load_more_button")) {
							break; // Reached the end
						}
						
						offset = response.getInt("offset");
					}
				}
			});
		}
	}
	
	static final class SnippetEpisodeObtainer {
		
		// General
		private static final String URL_API = "%{base_url}s/_snippet/%{type}s/%{count}d/%{offset}d/%{program_id}s";
		
		// Regex
		private static final Regex REGEX_SNIPPETS = Regex.of(
			"(?s)new\\s+InfiniteCarousel\\([^,]+,\\s*'/_snippet/([^/]+)/[^']+/(\\d+)',[^\\)]+\\)"
		);
		private static final Regex REGEX_NUM_SEASON = Regex.of("(?iu)S(\\d+)");
		private static final Regex REGEX_NUM_EPISODE = Regex.of("(?iu)(?:S?\\d+\\s+)?\\((\\d+)\\)");
		private static final Regex REGEX_EPISODE_TITLE = Regex.of("(?iu)^\\d+\\.\\s+epizoda");
		private static final Regex REGEX_PUNCTUATION = Regex.of("(?iu)([^\\p{L}\\p{Space}])");
		
		// Selectors
		private static final String SELECTOR_EPISODE = "article.w-full, article.molecule-video";
		
		// Formatters
		private static final DateTimeFormatter FORMATTER_DATETIME_CZECH
			= DateTimeFormatter.ofPattern("d. MMMM yyyy HH:mm", Locale.forLanguageTag("cs"));
		
		// Other
		private static SnippetEpisodeObtainer INSTANCE;
		
		private SnippetEpisodeObtainer() {
		}
		
		public static final SnippetEpisodeObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new SnippetEpisodeObtainer()) : INSTANCE;
		}
		
		private final Episode normalizeEpisode(Program program, URI uri, String title, String textSeason,
				String textEpisode) {
			int numSeason = 0;
			int numEpisode = 0;
			int startTextSeason = 0;
			int startTextEpisode = 0;
			Matcher matcher;
			
			// Find information about season number in the text
			if(textSeason != null) {
				if((matcher = REGEX_NUM_SEASON.matcher(textSeason)).find()) {
					startTextSeason = matcher.start(1);
				}
				
				numSeason = Utils.OfString.asIntOrDefault(textSeason, 10, false, startTextSeason, textSeason.length(), 0);
			}
			
			// Find information about episode number in the text
			if(textEpisode != null) {
				if((matcher = REGEX_NUM_EPISODE.matcher(textEpisode)).find()) {
					startTextEpisode = matcher.start(1);
				}
				
				numEpisode = Utils.OfString.asIntOrDefault(textEpisode, 10, false, startTextEpisode, textEpisode.length(), 0);
			}
			
			// Clean up title, if there is only the episode number plus the "episode" text
			if((matcher = REGEX_EPISODE_TITLE.matcher(title)).find()) {
				title = matcher.end() == title.length() ? null : title.substring(matcher.end());
			}
			
			return new Episode(program, uri, title, numEpisode, numSeason);
		}
		
		private final Episode parseEpisodeElementH4(Element elEpisode, Program program) {
			Element elLink = elEpisode.selectFirst("h4 > a");
			
			URI uri = Net.uri(elLink.attr("href"));
			String title = elLink.text();
			String textSeason = null;
			String textEpisode = null;
			
			Element elSubheading = elEpisode.selectFirst("h4 + span");
			
			if(elSubheading != null) {
				textSeason = elSubheading.text();
				textEpisode = elSubheading.text();
			}
			
			return normalizeEpisode(program, uri, title, textSeason, textEpisode);
		}
		
		private final String regexQuoteIgnorePunctuation(String string) {
			return ("\\Q" + REGEX_PUNCTUATION.replaceAll(string, "$1\\\\E?\\\\Q") + "\\E").replace("\\Q\\E", "");
		}
		
		private final Episode parseEpisodeElementH3(Element elEpisode, Program program, Map<Integer, Integer> indexes) {
			Element elLink = elEpisode.selectFirst("h3 > a");
			
			URI uri = Net.uri(elLink.attr("href"));
			String title = elLink.text();
			int numSeason = 0;
			int numEpisode = 0;
			
			Element elDate = elEpisode.selectFirst("h3 + div > span");
			String dateString = null;
			
			if(elDate != null) {
				dateString = elDate.text();
				numSeason = FORMATTER_DATETIME_CZECH.parse(dateString).get(ChronoField.YEAR);
			}
			
			Matcher matcher;
			String quotedProgramTitle = regexQuoteIgnorePunctuation(program.title());
			Regex regexProgramTitle = Regex.of("(?iu)" + quotedProgramTitle + "\\s+\\((\\d+\\.?)\\)");
			Regex regexProgramSubheading = Regex.of(
				"(?iu)^" + quotedProgramTitle + "(?:\\s+\\(?(?:"
					+ "\\d{1,2}\\.\\s*\\d{1,2}\\.\\s*\\d{4}(?:\\s+\\d{2}:\\d{2})?|" // Date (with time)
					+ "\\d+\\.?" // Episode number (with trailing dot)
					+ ")\\)?)?$"
			);
			
			if((matcher = regexProgramTitle.matcher(title)).find()) {
				numEpisode = Utils.OfString.asInt(matcher.group(1));
				title = "";
			}
			
			Element elSubheading = elEpisode.selectFirst("p + h3");
			
			if(elSubheading != null) {
				String prefix = elSubheading.previousElementSibling().text();
				
				if((matcher = regexProgramTitle.matcher(prefix)).find()) {
					numEpisode = Utils.OfString.asInt(matcher.group(1));
				} else if(!(matcher = regexProgramSubheading.matcher(prefix)).matches()) {
					title = prefix + (title.isEmpty() || prefix.endsWith(title) ? "" : " - " + title);
				}
			}
			
			if(numEpisode == 0) {
				numEpisode = indexes.compute(numSeason, (k, v) -> (v == null ? 1 : v) + 1) - 1;
			} else if(numSeason != 0) {
				final int number = numEpisode;
				indexes.compute(numSeason, (k, v) -> (v == null ? number : Math.max(number, v)) + 1);
			}
			
			if((matcher = regexProgramSubheading.matcher(title)).matches()) {
				title = "";
			}
			
			if(dateString != null) {
				title += (title.isEmpty() ? "" : " - ") + dateString;
			}
			
			return normalizeEpisode(program, uri, title, String.valueOf(numSeason), String.valueOf(numEpisode));
		}
		
		private final URI callUri(String uriTemplate, int offset, int count) {
			return Net.uri(Utils.format(uriTemplate, "count", count, "offset", offset));
		}
		
		public ListTask<Episode> getEpisodes(Program program) throws Exception {
			return ListTask.of((task) -> {
				URI programUri = program.uri();
				String response = request(programUri);
				
				if(response == null) {
					return; // Do not continue
				}
				
				Document document = HTML.parse(response, programUri);
				String snippetType = null;
				String programId = null;
				
				for(Element elScript : document.select("script")) {
					String content = elScript.html();
					Matcher matcher = REGEX_SNIPPETS.matcher(content);
					
					if(!matcher.find()) {
						continue;
					}
					
					snippetType = "videos-episode";
					programId = matcher.group(2);
				}
				
				if(programId == null) {
					for(Element elScript : document.select("script")) {
						String content = elScript.html();
						int index = content.indexOf("dataLayer.push");
						
						if(index < 0) {
							continue;
						}
						
						JSONCollection json = JSON.read(
							Utils.bracketSubstring(content, '{', '}', false, index, content.length())
						);
						
						snippetType = "programme_episodes";
						programId = String.valueOf(json.getInt("page.content.id"));
					}
				}
				
				if(programId == null) {
					return;
				}
				
				final int count = 64;
				int offset = 0;
				String baseUri = "https://" + programUri.getHost();
				String callUriTemplate = Utils.format(
					URL_API,
					"base_url", baseUri,
					"type", snippetType,
					"program_id", programId
				);
				
				// The content in this type of snippet may not have any information about
				// the episode number or the season number. Therefore, we must first obtain
				// the count of all the episodes and then to count down from the total,
				// so that it is in the correct order.
				if(snippetType.equals("videos-episode")) {
					// Since we must first find the end of the list, use binary-like search
					// technique. This results in lower number of requests for longer lists
					// and the same number of requests for very short ones. 
					int lo = offset, hi = lo + count;
					
					// First, by doubling the limit find the highest offset where there are no items.
					// This is the upper limit (hi). The lower limit (lo) will be the previous upper limit.
					for(; Web.size(Request.of(callUri(callUriTemplate, hi, count)).HEAD()) > 0L; lo = hi, hi *= 2);
					
					// Now we know that the actual limit is somewhere in the range <lo, hi>.
					// We can use binary search to find the actual value.
					while(hi - lo > count) {
						int mid = lo + (hi - lo) / 2;
						URI callUri = callUri(callUriTemplate, mid, count);
						if(Web.size(Request.of(callUri).HEAD()) <= 0L) hi = mid;
						else                                           lo = mid;
					}
					
					// The lower limit (lo) is the latest offset with non-empty content.
					offset = lo;
					
					for(Map<Integer, Integer> indexes = new HashMap<>(); offset >= 0; offset -= count) {
						URI callUri = callUri(callUriTemplate, offset, count);
						response = request(callUri);
						document = HTML.parse(response);
						
						for(Element elEpisode : Utils.asReversed(document.select(SELECTOR_EPISODE))) {
							Episode episode = parseEpisodeElementH3(elEpisode, program, indexes);
							
							if(!task.add(episode)) {
								return; // Do not continue
							}
						}
					}
				} else {
					for(;; offset += count) {
						URI callUri = callUri(callUriTemplate, offset, count);
						response = request(callUri);
						
						if(response == null || response.isEmpty()) {
							break;
						}
						
						document = HTML.parse(response);
						
						for(Element elEpisode : document.select(SELECTOR_EPISODE)) {
							Episode episode = parseEpisodeElementH4(elEpisode, program);
							
							if(!task.add(episode)) {
								return; // Do not continue
							}
						}
					}
				}
			});
		}
	}
	
	static final class DefaultMediaObtainer extends DefaultMediaObtainerBase {
		
		// URLs
		private static final String URL_API_PLAY;
		
		// Other
		private static DefaultMediaObtainer INSTANCE;
		
		static {
			URL_API_PLAY = "https://api.play-backend.iprima.cz/api/v1/products/id-%{product_id}s/play";
		}
		
		private DefaultMediaObtainer() {
		}
		
		public static final DefaultMediaObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new DefaultMediaObtainer()) : INSTANCE;
		}
		
		@Override
		protected String playURL() {
			return URL_API_PLAY;
		}
	}
	
	static final class DefaultMediaObtainerNewURL extends DefaultMediaObtainerBase {
		
		// URLs
		private static final String URL_API_PLAY;
		
		// Other
		private static DefaultMediaObtainerNewURL INSTANCE;
		
		static {
			URL_API_PLAY = "https://api.play-backend.iprima.cz/api/v1/products/play/ids-%{product_id}s";
		}
		
		private DefaultMediaObtainerNewURL() {
		}
		
		public static final DefaultMediaObtainerNewURL getInstance() {
			return INSTANCE == null ? (INSTANCE = new DefaultMediaObtainerNewURL()) : INSTANCE;
		}
		
		@Override
		protected String playURL() {
			return URL_API_PLAY;
		}
	}

	static final class PlayIdMediaObtainer {
		
		// URLs
		private static final String URL_API_PLAY
			= "https://api.play-backend.iprima.cz/api/v1/products/play/ids-%{play_id}s";
		
		// Regex
		private static final Regex REGEX_PLAY_IDS = Regex.of("videos\\s*=\\s*'([^']+)';");
		private static final Regex REGEX_NUM_EPISODE = Regex.of("^.*?/([^/]+?)$");
		
		// Selectors
		private static final String SELECTOR_SCRIPT = ".content > header .play-video > script";
		
		// Other
		private static PlayIdMediaObtainer INSTANCE;
		
		private PlayIdMediaObtainer() {
		}
		
		public static final PlayIdMediaObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new PlayIdMediaObtainer()) : INSTANCE;
		}
		
		private final String mediaTitle(JSONCollection videoData) {
			String programName = videoData.getString("productDetail.seriesTitle", "");
			String episodeName = videoData.getString("productDetail.episodeTitle", "");
			
			String numSeason = videoData.getString("productDetail.seasonNumber", "0");
			numSeason = String.format("%02d", Integer.valueOf(numSeason));
			
			String numEpisode = videoData.getString("productDetail.externalId", "0");
			Matcher matcherEpisode = REGEX_NUM_EPISODE.matcher(numEpisode);
			numEpisode = matcherEpisode.matches() ? matcherEpisode.group(1) : "0";
			numEpisode = String.format("%02d", Integer.valueOf(numEpisode));
			
			return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
		}
		
		public ListTask<Media> getMedia(URI uri, MediaEngine engine) throws Exception {
			return ListTask.of((task) -> {
				String response = request(uri);
				
				if(response == null) {
					return; // Do not continue
				}
				
				Document document = HTML.parse(response, uri);
				Element elScript = document.selectFirst(SELECTOR_SCRIPT);
				
				if(elScript == null) {
					return; // Cannot obtain the play ID
				}
				
				Matcher matcher = REGEX_PLAY_IDS.matcher(elScript.html());
				
				if(!matcher.find()) {
					return; // Cannot obtain the play ID
				}
				
				List<URI> urls = new ArrayList<>();
				String[] playIds = matcher.group(1).split(",");
				
				for(String playId : playIds) {
					urls.add(Net.uri(Utils.format(URL_API_PLAY, "play_id", playId)));
				}
				
				if(urls.isEmpty()) {
					return; // Nothing to do
				}
				
				HttpHeaders requestHeaders = authenticationRequestHeaders();
				MediaSource source = MediaSource.of(engine);
				
				for(URI urlPlay : urls) {
					String content = request(urlPlay, requestHeaders);
					
					if(content == null || content.isEmpty()) {
						continue;
					}
					
					JSONCollection data = JSON.read(content);
					URI sourceURI = urlPlay;
					
					for(JSONCollection videoData : data.collectionsIterable()) {
						String title = mediaTitle(videoData);
						
						for(JSONCollection streamInfo : videoData.getCollection("streamInfos").collectionsIterable()) {
							URI src = Net.uri(streamInfo.getString("url"));
							MediaLanguage language = MediaLanguage.ofCode(streamInfo.getString("lang.key"));
							
							List<Media> media = MediaUtils.createMedia(
								source, src, sourceURI, title, language, MediaMetadata.empty()
							);
							
							for(Media m : media) {
								if(!task.add(m)) {
									return; // Do not continue
								}
							}
						}
					}
				}
			});
		}
	}
	
	static final class SimpleExecutor<R> implements AutoCloseable {
		
		private final ExecutorService executor;
		private final AtomicReference<Exception> exception = new AtomicReference<>();
		private final CounterLock counter = new CounterLock();
		private final AtomicBoolean isShutdown = new AtomicBoolean();
		
		public SimpleExecutor(ExecutorService executor) {
			this.executor = Objects.requireNonNull(executor);
		}
		
		public static final <R> SimpleExecutor<R> ofFixed(int numThreads) {
			return new SimpleExecutor<>(Threads.Pools.newFixed(numThreads));
		}
		
		public final SimpleExecutor<R> addTask(CheckedRunnable runnable) {
			return addTask(Utils.callable(runnable));
		}
		
		public final SimpleExecutor<R> addTask(Callable<R> callable) {
			if(isShutdown.get()) {
				return this;
			}
			
			counter.increment();
			executor.submit(new Task(callable));
			return this;
		}
		
		public final void await() throws Exception {
			counter.await();
			
			executor.shutdown();
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
			
			Exception ex;
			if((ex = exception.get()) != null) {
				throw ex;
			}
		}
		
		@Override
		public void close() throws Exception {
			await();
		}
		
		private final class Task implements Callable<R> {
			
			private final Callable<R> callable;
			
			public Task(Callable<R> callable) {
				this.callable = Objects.requireNonNull(callable);
			}
			
			@Override
			public R call() throws Exception {
				boolean shouldShutdown = false;
				
				try {
					return callable.call();
				} catch(UncheckedIOException ex) {
					IOException cause = ex.getCause();
					
					if(cause instanceof ClosedByInterruptException) {
						// Interrupted, ignore
						return null;
					}
					
					shouldShutdown = exception.compareAndSet(null, ex);
					throw ex; // Propagate
				} catch(Exception ex) {
					shouldShutdown = exception.compareAndSet(null, ex);
					throw ex; // Propagate
				} finally {
					counter.decrement();
					
					if(shouldShutdown) {
						isShutdown.set(true);
						counter.free();
					}
				}
			}
		}
	}
	
	static abstract class ThreadedSpawnableTaskQueue<P, R> implements AutoCloseable {
		
		private final ExecutorService executor;
		private final AtomicReference<Exception> exception = new AtomicReference<>();
		private final CounterLock counter = new CounterLock();
		private final AtomicBoolean isShutdown = new AtomicBoolean();
		
		public ThreadedSpawnableTaskQueue(int maxThreads) {
			this.executor = Threads.Pools.newWorkStealing(maxThreads);
		}
		
		protected abstract R runTask(P arg) throws Exception;
		protected abstract boolean shouldShutdown(R val);
		
		public final ThreadedSpawnableTaskQueue<P, R> addTask(P arg) {
			if(isShutdown.get()) {
				return this;
			}
			
			counter.increment();
			executor.submit(new Task(arg));
			return this;
		}
		
		public final void await() throws Exception {
			counter.await();
			
			executor.shutdown();
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
			
			Exception ex;
			if((ex = exception.get()) != null) {
				throw ex;
			}
		}
		
		@Override
		public void close() throws Exception {
			await();
		}
		
		private final class Task implements Callable<R> {
			
			private final P arg;
			
			public Task(P arg) {
				this.arg = arg;
			}
			
			@Override
			public R call() throws Exception {
				boolean shouldShutdown = false;
				
				try {
					R val = runTask(arg);
					shouldShutdown = shouldShutdown(val);
					return val;
				} catch(UncheckedIOException ex) {
					IOException cause = ex.getCause();
					
					if(cause instanceof ClosedByInterruptException) {
						// Interrupted, ignore
						return null;
					}
					
					shouldShutdown = exception.compareAndSet(null, ex);
					throw ex; // Propagate
				} catch(Exception ex) {
					shouldShutdown = exception.compareAndSet(null, ex);
					throw ex; // Propagate
				} finally {
					counter.decrement();
					
					if(shouldShutdown) {
						isShutdown.set(true);
						counter.free();
					}
				}
			}
		}
	}
	
	static abstract class ConcurrentLoop<T> {
		
		protected final ExecutorService executor = Threads.Pools.newWorkStealing();
		protected final AtomicReference<Exception> exception = new AtomicReference<>();
		protected final CounterLock counter = new CounterLock();
		
		protected abstract void iteration(T value) throws Exception;
		
		protected final void await() throws Exception {
			counter.await();
			
			executor.shutdown();
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
			
			Exception ex;
			if((ex = exception.get()) != null) {
				throw ex;
			}
		}
		
		protected final void submit(T value) {
			counter.increment();
			executor.submit(new Iteration(value));
		}
		
		@SuppressWarnings("unchecked")
		public void iterate(T... values) throws Exception {
			for(T value : values) {
				submit(value);
			}
			
			await();
		}
		
		protected class Iteration implements Callable<Void> {
			
			protected final T value;
			
			public Iteration(T value) {
				this.value = value;
			}
			
			@Override
			public Void call() throws Exception {
				try {
					iteration(value);
					return null;
				} catch(Exception ex) {
					exception.compareAndSet(null, ex);
					throw ex; // Propagate
				} finally {
					counter.decrement();
				}
			}
		}
	}
	
	// Context-dependant Singleton instantiator
	static final class _Singleton {
		
		private static final Map<Class<?>, _Singleton> instances = new HashMap<>();
		
		private final Class<?> clazz;
		private Object instance;
		
		private _Singleton(Class<?> clazz) {
			this.clazz = clazz;
		}
		
		public static final <T> T getInstance() {
			Class<?> clazz = StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE).walk((stream) -> {
				return stream.filter((p) -> p.getDeclaringClass() != _Singleton.class)
						 .map(StackFrame::getDeclaringClass)
						 .findFirst().get();
			});
			return instances.computeIfAbsent(clazz, _Singleton::new).instance();
		}
		
		private final <T> T newInstance() {
			try {
				@SuppressWarnings("unchecked")
				Constructor<T> ctor = (Constructor<T>) clazz.getDeclaredConstructor();
				Reflection.setAccessible(ctor, true);
				T instance = ctor.newInstance();
				Reflection.setAccessible(ctor, false);
				return instance;
			} catch(Exception ex) {
				// Assume, the class is instantiable
			}
			// This should not happen
			return null;
		}
		
		protected final <T> T instance() {
			@SuppressWarnings("unchecked")
			T obj = (T) (instance == null ? (instance = newInstance()) : instance);
			return obj;
		}
	}
}