package sune.app.mediadown.media_engine.iprima;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.channels.ClosedByInterruptException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.net.Web.Response.OfStream;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.CheckedRunnable;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONType;
import sune.app.mediadown.util.Reflection;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Regex.ReusableMatcher;
import sune.app.mediadown.util.Utils;

final class IPrimaHelper {
	
	private static PluginBase PLUGIN;
	
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
	
	private static final StreamResponse requestResponse(URI uri, HttpHeaders headers) throws Exception {
		return new StreamResponse(Web.requestStream(
			Request.of(uri).headers(ensureAcceptGzip(headers)).GET()
		));
	}
	
	private static final InputStream requestStream(URI uri, HttpHeaders headers) throws Exception {
		return requestResponse(uri, headers).stream();
	}
	
	private static final String request(URI uri, HttpHeaders headers) throws Exception {
		try(InputStream stream = requestStream(uri, headers)) {
			return Utils.streamToString(stream);
		}
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
	
	private static final class StreamResponse implements AutoCloseable {
		
		private final Response.OfStream response;
		
		public StreamResponse(OfStream response) {
			this.response = Objects.requireNonNull(response);
		}
		
		public long size() {
			return Web.size(response.headers());
		}
		
		public InputStream stream() throws Exception {
			InputStream stream = response.stream();
			boolean isGzip = response.headers()
				.firstValue("Content-Encoding")
				.filter("gzip"::equalsIgnoreCase)
				.isPresent();
			
			if(isGzip) {
				stream = new GZIPInputStream(stream);
			}
			
			return stream;
		}
		
		public String content() throws Exception {
			return Utils.streamToString(stream());
		}
		
		@Override
		public void close() throws Exception {
			response.close();
		}
	}
	
	private static final class PlayIdExtractor {
		
		private static final Regex REGEX_PLAYER_INIT = Regex.of("function initPlayer\\d+");
		private static final Regex REGEX_PLAYER_OPTIONS = Regex.of("(?ms)playerOptions\\s*=\\s*(\\{.*?\\});");
		private static final Regex REGEX_PLAYER_TYPE = Regex.of("playerType:\\s*\"([^\"]+)\"");
		private static final Regex REGEX_PLAY_IDS = Regex.of("videos\\s*=\\s*'([^']+)';");
		
		private PlayIdExtractor() {
		}
		
		private static final boolean isMainPlayer(String content) {
			Matcher matcher;
			return (matcher = REGEX_PLAYER_OPTIONS.matcher(content)).find()
						&& (matcher = REGEX_PLAYER_TYPE.matcher(matcher.group(1))).find()
						&& "player".equals(matcher.group(1));
		}
		
		private static final List<String> extractPlayIds(String content) {
			Matcher matcher;
			return (matcher = REGEX_PLAY_IDS.matcher(content)).find()
						? List.of(matcher.group(1).split(","))
						: null;
		}
		
		public static final List<String> extract(Document document) throws Exception {
			ReusableMatcher matcher = REGEX_PLAYER_INIT.reusableMatcher();
			
			for(Element elScript : document.select("script:not([src])")) {
				String content = elScript.html();
				
				if(!matcher.reset(content).find()) {
					continue;
				}
				
				content = Utils.bracketSubstring(
					content, '{', '}', false, matcher.start(0), content.length()
				);
				
				if(!isMainPlayer(content)) {
					continue;
				}
				
				List<String> playIds;
				if((playIds = extractPlayIds(content)) != null) {
					return playIds;
				}
			}
			
			return List.of(); // No play ID found
		}
	}
	
	private static final class LinkedData {
		
		private static final Regex REGEX_EPISODE_NUMBER = Regex.of("\\s*\\((\\d+)\\)$");
		
		private LinkedData() {
		}
		
		private static final String videoMediaTitle(JSONCollection data) {
			return MediaUtils.mediaTitle(data.getString("name", "Video"), 0, 0, null);
		}
		
		private static final String episodeMediaTitle(JSONCollection data) {
			String programName = data.getString("partOfSeries.name", "");
			String episodeName = data.getString("name", "");
			String seasonName = data.getString("partOfSeason.name", "");
			int seasonNumber = data.getInt("partOfSeason.seasonNumber", 0);
			int episodeNumber = data.getInt("episodeNumber", 0);
			
			// TVSeries not present, use the TVEpisode name
			if(programName.isEmpty()) {
				programName = episodeName;
				episodeName = null;
			}
			
			// Unknown episode number, try to extract it from the program's name
			if(episodeNumber == 0) {
				Matcher matcher = REGEX_EPISODE_NUMBER.matcher(programName);
				
				if(matcher.find()) {
					episodeNumber = Integer.parseInt(matcher.group(1));
				}
				
				// Remove the episode number from the program's name
				programName = REGEX_EPISODE_NUMBER.replaceAll(programName, "");
			}
			
			// Unknown season number, try to extract if from the season's name
			if(seasonNumber == 0) {
				String[] parts = seasonName.split(" ");
				
				// The season number in the season name is in roman numerals,
				// convert it to an integer, starting from the end.
				seasonNumber = IntStream.range(0, parts.length)
					.mapToObj((i) -> parts[parts.length - i - 1])
					.map(Utils::romanToInteger)
					.findFirst().orElse(-1);
			}
			
			// Program's name cannot be empty, replace it with the episode number, if present
			if(programName.isEmpty() && episodeName != null) {
				programName = episodeName;
				episodeName = null;
			}
			
			// Remove program's name from the episode's name, if present
			if(episodeName != null) {
				String normProgramName = Utils.normalize(programName);
				String normEpisodeName = Utils.normalize(episodeName);
				
				Regex regex = Regex.of("(?i)" + Regex.quote(normProgramName));
				Matcher matcher = regex.matcher(normEpisodeName);
				
				if(matcher.find()) {
					// Remove the program's name by its position in the normalized string from
					// the original string. Should work fine for accented characters, since they
					// are each a single glyph, thus occupying a single position.
					episodeName = Utils.OfString.delete(
						episodeName, matcher.start(0), matcher.end(0)
					).strip();
				}
			}
			
			return MediaUtils.mediaTitle(programName, seasonNumber, episodeNumber, episodeName);
		}
		
		private static final JSONCollection findLinkedData(Document document, Set<String> types) {
			for(Element elScript : document.select("script[type='application/ld+json']")) {
				JSONCollection content = JSON.read(elScript.html());
				
				if(types.contains(content.getString("@type"))) {
					return content;
				}
			}
			
			return JSONCollection.empty();
		}
		
		public static final String mediaTitle(Document document) {
			JSONCollection ld = findLinkedData(
				document,
				Set.of("TVEpisode", "VideoObject")
			);
			
			switch(ld.getString("@type", "VideoObject")) {
				case "TVEpisode": return episodeMediaTitle(ld);
				case "VideoObject": return videoMediaTitle(ld);
				default: return null;
			}
		}
	}
	
	static final class DefaultMediaObtainer {
		
		private DefaultMediaObtainer() {
		}
		
		private static final URI playUri(String playId) {
			return Net.uri(Utils.format(
				"https://api.play-backend.iprima.cz/api/v1/products/play/ids-%{play_id}s",
				"play_id", playId
			));
		}
		
		private static final Iterable<JSONCollection> getStreamInfos(
			String playId
		) throws Exception {
			HttpHeaders requestHeaders = authenticationRequestHeaders();
			String content = request(playUri(playId), requestHeaders);
			
			if(content == null || content.isEmpty()) {
				return List.of(); // Do not continue
			}
			
			JSONCollection data = JSON.read(content);
			Iterable<JSONCollection> streamInfos;
			
			// The outer collection may be an array, if so, we have to flatten it first
			if(data.type() == JSONType.ARRAY) {
				streamInfos = Utils.iterable(
					Utils.stream(data.collectionsIterable())
						.flatMap(
							(c) -> c.hasCollection("streamInfos")
										? Utils.stream(
												c.getCollection("streamInfos")
												 .collectionsIterable()
										)
										: Stream.empty()
						)
						.iterator()
				);
			} else {
				streamInfos = Utils.iterable(
					Utils.stream(data.getCollection("streamInfos").collectionsIterable())
						.iterator()
				);
			}
			
			return streamInfos;
		}
		
		public static ListTask<Media> getMedia(URI uri, MediaEngine engine) throws Exception {
			return ListTask.of(PrimaCommon.handleErrors((task) -> {
				Document document = HTML.from(uri);
				
				String title = LinkedData.mediaTitle(document);
				URI sourceURI = uri;
				MediaSource source = MediaSource.of(engine);
				
				for(String playId : PlayIdExtractor.extract(document)) {
					for(JSONCollection info : getStreamInfos(playId)) {
						URI src = Net.uri(info.getString("url"));
						MediaLanguage language = MediaLanguage.ofCode(info.getString("lang.key"));
						
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
			}));
		}
	}
	
	static final class StaticProgramObtainer {
		
		private static final String SELECTOR_PROGRAMS
			= ".swiper-primary-programmes .molecule-programme-title > a,"
					+ ".programmes-list .molecule-programme-title > a";
		
		private StaticProgramObtainer() {
		}
		
		public static ListTask<Program> getPrograms(String urlPrograms) throws Exception {
			return ListTask.of(PrimaCommon.handleErrors((task) -> {
				Document document = HTML.from(Net.uri(urlPrograms));
				
				for(Element elItem : document.select(SELECTOR_PROGRAMS)) {
					URI uri = Net.uri(elItem.absUrl("href"));
					String title = elItem.text().strip();
					Program program = new Program(uri, title);
					
					if(!task.add(program)) {
						return; // Do not continue
					}
				}
			}));
		}
	}
	
	static final class SnippetProgramObtainer {
		
		private static final String URI_TEMPLATE = "https://%{subdomain}s.iprima.cz"
			+ "/snippet/programme/%{limit}d/%{offset}d/programme";
		
		private SnippetProgramObtainer() {
		}
		
		private static final URI pageUri(String uriTemplate, int offset, int limit) {
			return Net.uri(Utils.format(uriTemplate, "offset", offset, "limit", limit));
		}
		
		private static final boolean isActiveItem(Element elItem) {
			Element elImage;
			return (elImage = elItem.selectFirst("img[srcset]")) != null
						&& Stream.of(Utils.OfString.split(elImage.attr("srcset"), ","))
								.anyMatch((i) -> !i.contains("fallback-image.jpg"));
		}
		
		private static final boolean parseProgramList(
			ListTask<Program> task,
			Document document
		) throws Exception {
			for(Element elItem : document.select("article")) {
				if(!isActiveItem(elItem)) {
					continue;
				}
				
				Element elLink = elItem.selectFirst(".card-small-heading a");
				URI uri = Net.uri(elLink.absUrl("href"));
				String title = elLink.text().strip();
				Program program = new Program(uri, title);
				
				if(!task.add(program)) {
					return false; // Do not continue
				}
			}
			
			return true;
		}
		
		public static final ListTask<Program> getPrograms(String subdomain) {
			return ListTask.of(PrimaCommon.handleErrors((task) -> {
				String uriTemplate = Utils.format(URI_TEMPLATE, "subdomain", subdomain);
				final int limit = 100;
				
				for(int offset = 0;; offset += limit) {
					URI pageUri = pageUri(uriTemplate, offset, limit);
					
					try(StreamResponse response = requestResponse(pageUri, HEADERS_GZIP)) {
						String content;
						
						if(response.size() == 0L
								|| (content = response.content()).isBlank()) {
							break;
						}
						
						Document document = HTML.parse(content, pageUri);
						
						if(!parseProgramList(task, document)) {
							return; // Do not continue
						}
					}
				}
			}));
		}
	}
	
	static final class StaticEpisodeObtainer {
		
		private static final String SELECTOR_EPISODES = "#episodes-video-holder > article";
		
		private StaticEpisodeObtainer() {
		}
		
		public static final ListTask<Episode> getEpisodes(Program program) {
			return ListTask.of(PrimaCommon.handleErrors((task) -> {
				Document document = HTML.from(program.uri());
				
				for(Element elItem : document.select(SELECTOR_EPISODES)) {
					Element elLink = elItem.selectFirst("a:not(.hover-zoom-img)");
					URI uri = Net.uri(elLink.absUrl("href"));
					String title = elLink.text().strip();
					Episode episode = new Episode(program, uri, title);
					
					if(!task.add(episode)) {
						return; // Do not continue
					}
				}
			}));
		}
	}
	
	static final class SnippetEpisodeObtainer {
		
		private static final String URI_TEMPLATE = "https://%{host}s"
			+ "/snippet/%{type}s/%{limit}d/%{offset}d/%{program_id}s";
		
		private static final Regex REGEX_SNIPPETS = Regex.of(
			"(?s)new\\s+InfiniteCarousel\\([^,]+,\\s*'/snippet/episode/limit/offset/([^']+)',[^\\)]+\\)"
		);
		private static final Regex REGEX_EPISODE_TITLE = Regex.of("(?iu)^\\d+\\.\\s+epizoda");
		private static final Regex REGEX_PUNCTUATION = Regex.of("(?iu)([^\\p{L}\\p{Space}])");
		
		private static final String SELECTOR_EPISODE = "article.molecule-video";
		
		private static final DateTimeFormatter FORMATTER_DATETIME_CZECH
			= DateTimeFormatter.ofPattern("d. MMMM yyyy HH:mm", Locale.forLanguageTag("cs"));
		
		private SnippetEpisodeObtainer() {
		}
		
		private static final String regexQuoteIgnorePunctuation(String string) {
			return (
				"\\Q" + REGEX_PUNCTUATION.replaceAll(string, "$1\\\\E?\\\\Q") + "\\E"
			).replace("\\Q\\E", "");
		}
		
		private static final Episode parseEpisodeItem(
			Element elItem, Program program, Map<Integer, Integer> indexes
		) {
			Element elLink = elItem.selectFirst("h3 > a");
			URI uri = Net.uri(elLink.attr("href"));
			String title = elLink.text().strip();
			
			String dateString = null;
			int numSeason = 0;
			int numEpisode = 0;
			
			// Use the episode's date year as the season number, if present
			Element elDate;
			if((elDate = elItem.selectFirst("h3 + div > span")) != null) {
				dateString = elDate.text().strip();
				numSeason = FORMATTER_DATETIME_CZECH.parse(dateString).get(ChronoField.YEAR);
			}
			
			String quotedProgramTitle = regexQuoteIgnorePunctuation(program.title());
			Regex regexProgramTitle = Regex.of("(?iu)" + quotedProgramTitle + "\\s+\\((\\d+\\.?)\\)");
			Regex regexProgramSubheading = Regex.of(
				"(?iu)^" + quotedProgramTitle + "(?:\\s+\\(?(?:"
					+ "\\d{1,2}\\.\\s*\\d{1,2}\\.\\s*\\d{4}(?:\\s+\\d{2}:\\d{2})?|" // Date (with time)
					+ "\\d+\\.?" // Episode number (with trailing dot)
					+ ")\\)?)?$"
			);
			
			// Check whether the episode's title in in the format "{name} ({num_episode})"
			Matcher matcher;
			if((matcher = regexProgramTitle.matcher(title)).find()) {
				numEpisode = Utils.OfString.asInt(matcher.group(1));
				title = "";
			}
			
			// Also check the subheading, if present, and format the title accordingly
			Element elSubheading;
			if((elSubheading = elItem.selectFirst("p + h3")) != null) {
				String prefix = elSubheading.previousElementSibling().text();
				
				if((matcher = regexProgramTitle.matcher(prefix)).find()) {
					numEpisode = Utils.OfString.asInt(matcher.group(1));
				} else if(!(matcher = regexProgramSubheading.matcher(prefix)).matches()) {
					title = prefix + (title.isEmpty() || prefix.endsWith(title) ? "" : " - " + title);
				}
			}
			
			if(numEpisode == 0) {
				// Calculate the episode number from the list
				numEpisode = indexes.compute(numSeason, (k, v) -> (v == null ? 1 : v) + 1) - 1;
			} else if(numSeason != 0) {
				// Update the episode number of the season
				final int number = numEpisode;
				indexes.compute(numSeason, (k, v) -> (v == null ? number : Math.max(number, v)) + 1);
			}
			
			if((matcher = regexProgramSubheading.matcher(title)).matches()) {
				title = "";
			}
			
			if(dateString != null) {
				title += (title.isEmpty() ? "" : " - ") + dateString;
			}
			
			// Clean up title, if there is only the episode number plus the "episode" text
			if((matcher = REGEX_EPISODE_TITLE.matcher(title)).find()) {
				title = matcher.end() == title.length() ? null : title.substring(matcher.end());
			}
			
			return new Episode(program, uri, title, numEpisode, numSeason);
		}
		
		private static final URI pageUri(String uriTemplate, int offset, int limit) {
			return Net.uri(Utils.format(uriTemplate, "limit", limit, "offset", offset));
		}
		
		public static ListTask<Episode> getEpisodes(Program program) throws Exception {
			return ListTask.of(PrimaCommon.handleErrors((task) -> {
				URI programUri = program.uri();
				Document document = HTML.from(programUri);
				ReusableMatcher matcher = REGEX_SNIPPETS.reusableMatcher();
				String programId = null;
				
				for(Element elScript : document.select("script:not([src])")) {
					String content = elScript.html();
					
					if(matcher.reset(content).find()) {
						programId = matcher.group(1);
						break;
					}
				}
				
				if(programId == null) {
					return;
				}
				
				final int limit = 64;
				int offset = 0;
				String uriTemplate = Utils.format(
					URI_TEMPLATE,
					"host", programUri.getHost(),
					"type", "episode",
					"program_id", programId
				);
				
				// Since we must first find the end of the list, use binary-like search
				// technique. This results in lower number of requests for longer lists
				// and the same number of requests for very short ones.
				int lo = offset, hi = lo + limit;
				
				// First, by doubling the limit find the highest offset where there are no items.
				// This is the upper limit (hi). The lower limit (lo) will be the previous upper limit.
				for(; Web.size(Request.of(pageUri(uriTemplate, hi, limit)).HEAD()) > 0L; lo = hi, hi *= 2);
				
				// Now we know that the actual limit is somewhere in the range <lo, hi>.
				// We can use binary search to find the actual value.
				while(hi - lo > limit) {
					int mid = lo + (hi - lo) / 2;
					URI pageUri = pageUri(uriTemplate, mid, limit);
					if(Web.size(Request.of(pageUri).HEAD()) <= 0L) hi = mid;
					else                                           lo = mid;
				}
				
				// The lower limit (lo) is the latest offset with non-empty content.
				offset = lo;
				
				for(Map<Integer, Integer> indexes = new HashMap<>(); offset >= 0; offset -= limit) {
					document = HTML.from(pageUri(uriTemplate, offset, limit));
					
					for(Element elEpisode : Utils.asReversed(document.select(SELECTOR_EPISODE))) {
						Episode episode = parseEpisodeItem(elEpisode, program, indexes);
						
						if(!task.add(episode)) {
							return; // Do not continue
						}
					}
				}
			}));
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