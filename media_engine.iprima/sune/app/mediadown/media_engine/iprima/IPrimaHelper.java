package sune.app.mediadown.media_engine.iprima;

import java.net.CookieManager;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import sune.app.mediadown.Episode;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.Program;
import sune.app.mediadown.Shared;
import sune.app.mediadown.engine.MediaEngine;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media_engine.iprima.IPrimaEngine.IPrima;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.util.CheckedBiFunction;
import sune.app.mediadown.util.CheckedFunction;
import sune.app.mediadown.util.CheckedSupplier;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Reflection2;
import sune.app.mediadown.util.Reflection3;
import sune.app.mediadown.util.Threads;
import sune.app.mediadown.util.TriFunction;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.WorkerProxy;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDCollectionType;
import sune.util.ssdf2.SSDF;
import sune.util.ssdf2.SSDType;

final class IPrimaHelper {
	
	private static PluginBase PLUGIN;
	
	private static final int CALLBACK_EXIT = -1;
	private static final String FORMAT_EPISODE_TITLE = "%{season}s - %{episode}s";
	private static final Pattern REGEX_EPISODE_NAME = Pattern.compile("^\\d+\\.[^\\-]+-\\s+(.*)$");
	
	private static final ConcurrentVarLazyLoader<CookieManager> cookieManager
		= ConcurrentVarLazyLoader.of(IPrimaHelper::ensureCookieManager);
	private static final ConcurrentVarLazyLoader<HttpClient> httpClient
		= ConcurrentVarLazyLoader.of(IPrimaHelper::buildHttpClient);
	private static final ConcurrentVarLazyLoader<HttpRequest.Builder> httpRequestBuilder
		= ConcurrentVarLazyLoader.of(IPrimaHelper::buildHttpRequestBuilder);
	
	private static final CookieManager ensureCookieManager() throws Exception {
		Reflection3.invokeStatic(Web.class, "ensureCookieManager");
		return (CookieManager) Reflection2.getField(Web.class, null, "COOKIE_MANAGER");
	}
	
	private static final HttpClient buildHttpClient() throws Exception {
		return HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(5000))
					.executor(Threads.Pools.newWorkStealing())
					.followRedirects(Redirect.NORMAL)
					.cookieHandler(cookieManager.value())
					.version(Version.HTTP_2)
					.build();
	}
	
	private static final HttpRequest.Builder buildHttpRequestBuilder() throws Exception {
		return HttpRequest.newBuilder()
					.GET()
					.setHeader("User-Agent", Shared.USER_AGENT);
	}
	
	private static final HttpRequest.Builder maybeAddHeaders(HttpRequest.Builder request, Map<String, String> headers) {
		if(!headers.isEmpty()) {
			request.headers(
				headers.entrySet().stream()
					.flatMap((e) -> Stream.of(e.getKey(), e.getValue()))
					.toArray(String[]::new)
			);
		}
		return request;
	}
	
	private static final String internal_request(URI uri, Map<String, String> headers) throws Exception {
		HttpRequest request = maybeAddHeaders(httpRequestBuilder.value().copy().uri(uri), headers).build();
		HttpResponse<String> response = httpClient.value().sendAsync(request, BodyHandlers.ofString(Shared.CHARSET)).join();
		return response.body();
	}
	
	private static final String internal_request(URI uri) throws Exception {
		return internal_request(uri, Map.of());
	}
	
	private static final <T> boolean internal_listAdder(WorkerProxy proxy, CheckedBiFunction<WorkerProxy, T, Boolean> function,
	        List<T> list, T object) throws Exception {
		return list.add(object) && function.apply(proxy, object);
	}
	
	private static final <S, T> void internal_loopOffset(IPrima iprima, S source, int offset, Map<String, Object> urlArgs,
	        CheckedFunction<Map<String, Object>, String> urlBuilder,
	        CheckedPentaFunction<IPrima, S, String, Map<String, Object>, CheckedFunction<T, Boolean>, Integer> callback,
	        CheckedFunction<T, Boolean> listAdder) throws Exception {
		do {
			urlArgs.put("offset", offset);
			URL url = Utils.url(urlBuilder.apply(urlArgs));
			String response = internal_request(Utils.uri(url));
			offset = callback.apply(iprima, source, response, urlArgs, listAdder);
		} while(offset >= 0);
	}
	
	// Accumulator must be thread-safe, e.g. ConcurrentSkipListSet
	private static final <S, T> void internal_loopOffsetThreaded(IPrima iprima, S source, int offset, int step,
			Map<String, Object> urlArgs, CheckedFunction<Map<String, Object>, String> urlBuilder,
	        CheckedPentaFunction<IPrima, S, String, Map<String, Object>, CheckedFunction<T, Boolean>, Integer> callback,
	        TriFunction<T, Integer, Integer, Wrapper<T>> wrapperCtor, Set<Wrapper<T>> accumulator) throws Exception {
		(new ThreadedSpawnableTaskQueue<Integer, Integer>(2) {
			
			private final AtomicInteger lastOffset = new AtomicInteger();
			
			private final boolean threadSafeListAdder(T val, int offset, int index) throws Exception {
				return accumulator.add(wrapperCtor.apply(val, offset, index)) || true;
			}
			
			@Override
			protected Integer runTask(Integer off) throws Exception {
				Map<String, Object> localURLArgs = new LinkedHashMap<>(urlArgs);
				localURLArgs.put("offset", off);
				URL url = Utils.url(urlBuilder.apply(localURLArgs));
				String response = internal_request(Utils.uri(url));
				AtomicInteger index = new AtomicInteger();
				int result = callback.apply(iprima, source, response, urlArgs, (val) -> {
					return threadSafeListAdder(val, off, index.getAndIncrement());
				});
				if(!shouldShutdown(result)) {
					if(off == 0) {
						int a = off + step;
						int b = a   + step;
						lastOffset.set(b + step);
						addTask(a);
						addTask(b);
					} else {
						int a = off, next;
						for(; (next = lastOffset.compareAndExchange(a, a + step)) != a; a = next);
						addTask(next);
					}
				}
				return result;
			}
			
			@Override
			protected boolean shouldShutdown(Integer val) {
				return val == CALLBACK_EXIT;
			}
		}).addTask(0).await();
	}
	
	public static final int compareNatural(String a, String b) {
		// See: https://stackoverflow.com/posts/comments/13599980
		String[] sa = a.split("(?<=\\d+)(?!\\d)|(?<!\\d)(?=\\d+)");
		String[] sb = b.split("(?<=\\d+)(?!\\d)|(?<!\\d)(?=\\d+)");
		
		int la = sa.length, lb = sb.length;
		for(int i = 0, l = Math.min(la, lb), cmp; i < l; ++i) {
			if(sa[i].isEmpty()) {
				cmp = sb[i].isEmpty() ? 0 : -1;
			} else if(Character.isDigit(sa[i].codePointAt(0))
						&& Character.isDigit(sb[i].codePointAt(0))) {
				int ia = Integer.parseInt(sa[i]);
				int ib = Integer.parseInt(sb[i]);
				// Reverse the integer comparing, so that the latest
				// episodes are first.
				cmp = Integer.compare(ib, ia);
			} else {
				cmp = sa[i].compareTo(sb[i]);
			}
			
			if(cmp != 0) return cmp;
		}
		
		return Integer.compare(la, lb);
	}
	
	public static final void setPlugin(PluginBase plugin) {
		PLUGIN = plugin;
	}
	
	public static final PluginConfiguration configuration() {
		return PLUGIN.getContext().getConfiguration();
	}
	
	static final class DefaultEpisodeObtainer {
		
		// General
		private static final String URL_API_IPRIMA;
		
		// Selectors
		private static final String	SELECTOR_EPISODE = ".program";
		private static final String	SELECTOR_EPISODE_LINK = "> a";
		private static final String	CLASS_EPISODES_WRAPPER = "organism--section-area--program-videos-section-area";
		private static final String	CLASS_EPISODES_SEASON = "organism--section-area--program-videos-section-area--season-title";
		private static final String	SELECTOR_SEASONS = ".section--view--program-videos-section--seasons";
		private static final String	SELECTOR_SEASON_DESCRIPTION = ".description";
		private static final String	SELECTOR_SEASON_TITLE = ".title";
		private static final List<String> SELECTOR_PRICE_WRAPPERS = List.of("bundle-node", "episode-latest");
		private static final String	SELECTOR_PRICE_WRAPPER;
		
		// Other
		private static final String	TYPE_EPISODES = "episodes";
		private static DefaultEpisodeObtainer INSTANCE;
		
		static {
			URL_API_IPRIMA = "https://www.iprima.cz/iprima-api/View_Entity_Node_Program/ThemedContent/More"
			        + "?id=%{id}s" + "&offset=%{offset}d" + "&subpage_type=%{type}s";
			SELECTOR_PRICE_WRAPPER = Utils.join(",", SELECTOR_PRICE_WRAPPERS.stream()
			        .map((s) -> ".component--scope--" + s + "--picture--bottom--price")
			        .toArray(String[]::new));
		}
		
		private DefaultEpisodeObtainer() {
		}
		
		public static final DefaultEpisodeObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new DefaultEpisodeObtainer()) : INSTANCE;
		}
		
		private final String urlBuilderIPrimaAPI(Map<String, Object> urlArgs) {
			return Utils.format(URL_API_IPRIMA, urlArgs);
		}
		
		private final String getEpisodeSeasonTitle(Element element) {
			String season = null;
			do {
				Element parent = element.parent();
				if(parent.hasClass(CLASS_EPISODES_WRAPPER)) {
					for(Element prev = element.previousElementSibling(); prev != null; prev = prev
					        .previousElementSibling()) {
						if(prev.hasClass(CLASS_EPISODES_SEASON)) {
							season = prev.text();
							break;
						}
					}
				}
				element = parent;
			} while(season == null && element.hasParent());
			return season;
		}
		
		private final boolean getEpisodesFromDocument(Program program, Document document, String seasonTitle,
		        CheckedFunction<Episode, Boolean> functionAddToList) throws Exception {
			for(Element elEpisode : document.select(SELECTOR_EPISODE)) {
				// Only add free episodes (those with no price tag)
				if(elEpisode.selectFirst(SELECTOR_PRICE_WRAPPER) != null)
					continue;
				Element elEpisodeLink = elEpisode.selectFirst(SELECTOR_EPISODE_LINK);
				// Sometimes an episode is not playable due to e.g. licensing, just skip it
				if(elEpisodeLink == null) continue;
				String episodeTitle = elEpisodeLink.attr("title");
				String episodeSeasonTitle = seasonTitle == null ? getEpisodeSeasonTitle(elEpisode) : seasonTitle;
				if(episodeSeasonTitle != null) {
					episodeTitle = Utils.format(FORMAT_EPISODE_TITLE, "season", episodeSeasonTitle, "episode",
					                            elEpisodeLink.attr("title"));
				}
				String episodeURL = Utils.urlFix(elEpisodeLink.attr("href"), true);
				Episode episode = new Episode(program, Utils.uri(episodeURL), episodeTitle);
				if(!functionAddToList.apply(episode))
					return false; // Do not continue
			}
			return true;
		}
		
		private final int callback(IPrima iprima, Program program, String response, Map<String, Object> args,
				CheckedFunction<Episode, Boolean> functionAddToList) throws Exception {
			// The response must be fixed first, since it contains escaped characters
			// that are unescaped incorrectly while reading the respective data node.
			response = response.replaceAll("\\\\(.)", "\\\\\\\\$1");
			SSDCollection data = SSDF.readJSON(response);
			String content = data.getDirectString("related_content", null);
			if(content == null)
				return CALLBACK_EXIT; // Do not continue
			// Another fixing and tidying up
			content = Utils.replaceUnicode4Digits(content);
			content = content.replace("\\n", "");
			content = content.replace("\\/", "/");
			Document document = Utils.parseDocument(content);
			if(!getEpisodesFromDocument(program, document, null, functionAddToList))
				return CALLBACK_EXIT; // Do not continue
			// Check if in the next iteration would be any items
			if(data.getDirectBoolean("hide_load_more_button"))
				return CALLBACK_EXIT;
			// If so, return the next offset, provided in the response
			return data.getDirectInt("offset");
		}
		
		public List<Episode> getEpisodes(Program program, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
			String id = program.get("id");
			if(id == null || id.isEmpty())
				// Cannot do anything without an ID
				return null;
			List<Episode> episodes = new ArrayList<>();
			CheckedFunction<Episode, Boolean> listAdder = ((episode) -> {
				return internal_listAdder(proxy, function, episodes, episode);
			});
			// Check for movies, they do not have any episodes
			if(program.get("type").equals("VideoNode")) {
				// Nothing to do, just set the program information
				Episode episode = new Episode(program, program.uri(), program.title());
				if(!listAdder.apply(episode))
					return null;
			} else {
				// Since there is currently no known way to get episodes for all seasons
				// or only for a specific season, we have to examine the whole document
				// structure to obtain all the information about whether it can actually
				// be done the traditional way.
				Document document = Utils.document(program.uri());
				// First check if there are any seasons present
				Element elSeasons = document.selectFirst(SELECTOR_SEASONS);
				if(elSeasons != null) {
					for(Element elSeason : elSeasons.children()) {
						// It is really only a season if there is number of episodes specified
						// in the description. Apart from seasons, also include recently aired
						// episodes. This will probably result in duplicates but it should not
						// matter.
						if(elSeason.selectFirst(SELECTOR_SEASON_DESCRIPTION).text().trim().isEmpty()
								&& !Utils.urlBasename(elSeason.absUrl("href")).contains("nedavno-odvysilane"))
							continue;
						String seasonTitle = elSeason.selectFirst(SELECTOR_SEASON_TITLE).text().trim();
						String seasonURL = Utils.urlFix(elSeason.attr("href"), true);
						Document seasonDocument = Utils.document(seasonURL);
						// Extract all the episodes from the season document
						if(!getEpisodesFromDocument(program, seasonDocument, seasonTitle, listAdder))
							return null;
					}
				} else {
					IPrima iprima = program.get("source");
					Set<Wrapper<Episode>> accumulator = new ConcurrentSkipListSet<>();
					// If there are no seasons, we can get all the episodes traditionally,
					// i.e. the request loop way.
					internal_loopOffsetThreaded(iprima, program, 0, 16, Utils.toMap("id", id, "type", TYPE_EPISODES),
						this::urlBuilderIPrimaAPI, this::callback, EpisodeWrapper::new, accumulator);
					// Add all obtained episodes from the accumulator manually
					for(Wrapper<Episode> ep : accumulator) listAdder.apply(ep.value());
				}
			}
			return episodes;
		}
	}
	
	static abstract class DefaultMediaObtainerBase {
		
		protected DefaultMediaObtainerBase() {
		}
		
		protected abstract String playURL();
		
		public List<Media> getMedia(String url, WorkerProxy proxy, CheckedBiFunction<WorkerProxy, Media, Boolean> function,
				MediaEngine engine) throws Exception {
			List<Media> sources = new ArrayList<>();
			Document document = Utils.document(url);
			String productId = null;
			// (1) Extract product ID from iframe's URL
			Element elIframe;
			if((elIframe = document.selectFirst("iframe.video-embed")) != null) {
				productId = Utils.urlParams(elIframe.attr("src")).getOrDefault("id", null);
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
			if(productId == null) return null;
			URL configURL = Utils.url(Utils.format(playURL(), "product_id", productId));
			// It is important to specify the referer, otherwise the response code is 403.
			Map<String, String> requestHeaders = Utils.toMap("Referer", "https://www.iprima.cz/");
			try {
				// Try to log in to the iPrima website using the internal account to have HD sources available.
				IPrimaAuthenticator.SessionData sessionData = IPrimaAuthenticator.getSessionData();
				Utils.merge(requestHeaders, sessionData.requestHeaders());
			} catch(Exception ex) {
				// Notify the user that the HD sources may not be available due to inability to log in.
				MediaDownloader.error(new IllegalStateException("Unable to log in to the iPrima website.", ex));
			}
			String content = internal_request(Utils.uri(configURL), requestHeaders);
			if(content == null || content.isEmpty()) return null;
			SSDCollection data = SSDF.readJSON(content);
			// Try to obtain the full title of the media
			String title = "";
			Element elMediaInfo = document.selectFirst("script[type='application/ld+json']");
			if(elMediaInfo != null) {
				SSDCollection mediaInfo = JSON.read(elMediaInfo.html());
				String programName = mediaInfo.getString("partOfSeries.name", "");
				String seasonName = mediaInfo.getString("partOfSeason.name", "");
				String rawEpisode = mediaInfo.getDirectString("episodeNumber", "0");
				String numEpisode = String.format("%02d", Integer.valueOf(rawEpisode));
				// If a video is not part of any series, there is only a name of the video
				if(programName.isEmpty()) {
					programName = mediaInfo.getDirectString("name", "");
					String regexNumEpisode = "\\s*\\((\\d+)\\)$";
					// Extract the episode number, if it exists in the name
					if(rawEpisode.equals("0")) {
						Matcher matcher = Pattern.compile(regexNumEpisode).matcher(programName);
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
						SSDCollection json = SSDF.readJSON(con);
						fullName = json.getDirectString("title", "");
						if(!fullName.isEmpty()) break;
					}
				}
				// Finally, extract only the episode name from the full name
				String episodeName = "";
				Matcher matcher = REGEX_EPISODE_NAME.matcher(fullName);
				if(matcher.matches()) {
					episodeName = matcher.group(1);
					// The episode name can be in the format "[program_name] - [season] ([episode])",
					// which is not desirable.
					String regex = "^" + Pattern.quote(programName) + " "
					                   + Pattern.quote(seasonRoman) + " \\("
							           + Pattern.quote(rawEpisode) + "\\)$";
					if(Pattern.matches(regex, episodeName))
						episodeName = "";
				}
				title = MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName, splitSeasonAndEpisode);
			}
			
			// The outer collection may be an array, we have to flatten it first, if it is
			SSDCollection streamInfos;
			if(data.getType() == SSDCollectionType.ARRAY) {
				streamInfos = SSDCollection.emptyArray();
				Utils.stream(data.collectionsIterator())
					.flatMap((c) -> Utils.stream(c.getDirectCollection("streamInfos").collectionsIterator()))
					.forEach(streamInfos::add);
			} else {
				streamInfos = data.getDirectCollection("streamInfos");
			}
			
			URI sourceURI = Utils.uri(url);
			MediaSource source = MediaSource.of(engine);
			for(SSDCollection streamInfo : streamInfos.collectionsIterable()) {
				String src = streamInfo.getDirectString("url");
				MediaLanguage language = MediaLanguage.ofCode(streamInfo.getString("lang.key"));
				List<Media> media = MediaUtils.createMedia(source, Utils.uri(src), sourceURI, title,
					language, MediaMetadata.empty());
				for(Media s : media) {
					sources.add(s);
					if(!function.apply(proxy, s))
						return null; // Do not continue
				}
			}
			return sources;
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
	
	static final class GraphQLProgramObtainer {
		
		// General
		private static final String URL_API;
		private static final String QUERY_TEMPLATE;
		private static final String QUERY_ID_SERIES = "web-series";
		private static final String QUERY_ID_MOVIES = "web-movies";
		
		// Other
		private static GraphQLProgramObtainer INSTANCE;
		
		static {
			URL_API = "https://api.iprima.cz/graphql?query=%{query}s";
			QUERY_TEMPLATE
	            = "{\r\n" +
			      "	strip(\r\n" +
			      "		device: web\r\n" +
			      "		id: \"%{id}s\"\r\n" +
			      "		paging: { count: %{count}d, offset: %{offset}d }\r\n" +
			      "		sort: title_asc\r\n" +
			      "	) {\r\n" +
			      "		content {\r\n" +
			      "			__typename\r\n" +
			      "			... on VideoNode {\r\n" +
			      "				nid\r\n" +
			      "				title\r\n" +
			      "				webUrl\r\n" +
			      "				price\r\n" +
			      "			}\r\n" +
			      "			... on ProgramNode {\r\n" +
			      "				nid\r\n" +
			      "				title\r\n" +
			      "				webUrl\r\n" +
			      "				price\r\n" +
			      "			}\r\n" +
			      "		}\r\n" +
			      "	}\r\n" +
			      "}";
		}
		
		private GraphQLProgramObtainer() {
		}
		
		public static final GraphQLProgramObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new GraphQLProgramObtainer()) : INSTANCE;
		}
		
		private static final String internal_buildQuery(String id, int count, int offset) {
			return Utils.format(QUERY_TEMPLATE, "id", id, "count", count, "offset", offset);
		}
		
		private static final String internal_urlBuilderAPI(Map<String, Object> urlArgs) {
			String query = internal_buildQuery((String) urlArgs.get("id"), (Integer) urlArgs.get("count"), (Integer) urlArgs.get("offset"));
			return Utils.format(URL_API, "query", Utils.encodeURL(query));
		}
		
		private static final int internal_callback_getPrograms(IPrima iprima, Object source, String response, Map<String, Object> args,
				CheckedFunction<Program, Boolean> functionAddToList) throws Exception {
			// The response must be fixed first, since it contains escaped characters
			// that are unescaped incorrectly while reading the respective data node.
			response = Utils.replaceUnicode4Digits(response);
			SSDCollection data = SSDF.readJSON(response);
			SSDCollection content = data.getCollection("data.strip.content");
			if(content.length() <= 0)
				return CALLBACK_EXIT;
			for(SSDCollection coll : content.collectionsIterable()) {
				// Only add free programs
				if(coll.getDirectObject("price").getType() != SSDType.NULL)
					continue;
				String type = coll.getDirectString("__typename");
				String id = coll.getDirectString("nid");
				String title = coll.getDirectString("title");
				String url = coll.getDirectString("webUrl");
				Program program = new Program(Utils.uri(url), title, "source", iprima, "id", id, "type", type);
				if(!functionAddToList.apply(program))
					return CALLBACK_EXIT; // Do not continue
			}
			return (Integer) args.get("offset") + (Integer) args.get("count");
		}
		
		public List<Program> getPrograms(IPrima iprima, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			List<Program> programs = new ArrayList<>();
			CheckedFunction<Program, Boolean> listAdder = ((program) -> {
				return internal_listAdder(proxy, function, programs, program);
			});
			internal_loopOffset(iprima, null, 0, Utils.toMap("id", QUERY_ID_SERIES, "count", 64),
				GraphQLProgramObtainer::internal_urlBuilderAPI,
				GraphQLProgramObtainer::internal_callback_getPrograms,
				listAdder);
			internal_loopOffset(iprima, null, 0, Utils.toMap("id", QUERY_ID_MOVIES, "count", 64),
				GraphQLProgramObtainer::internal_urlBuilderAPI,
				GraphQLProgramObtainer::internal_callback_getPrograms,
				listAdder);
			return programs;
		}
	}
	
	static final class PrimaAPIProgramObtainer {
		
		private static final String URL_API;
		private static final String TYPE_MOVIE = "Movies";
		private static final String TYPE_SHOW = "Series";
		private static final String GRAPHQL_TYPE_MOVIE = "VideoNode";
		private static final String GRAPHQL_TYPE_SHOW = "ProgramNode";
		
		// Other
		private static PrimaAPIProgramObtainer INSTANCE;
		
		static {
			URL_API = "https://www.iprima.cz/iprima-api/ListWithFilter/%{type}s/Content"
					+ "?filter=all"
					+ "&channel_restriction=%{subdomain}s";
		}
		
		private PrimaAPIProgramObtainer() {
		}
		
		public static final PrimaAPIProgramObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new PrimaAPIProgramObtainer()) : INSTANCE;
		}
		
		private static final int callback(IPrima iprima, String response, String type, CheckedFunction<Program, Boolean> functionAddToList,
				String graphQLType) throws Exception {
			// The response must be fixed first, since it contains escaped characters
			// that are unescaped incorrectly while reading the respective data node.
			response = response.replaceAll("\\\\u0022", "\\\\\""); // Escape double quotes
			response = Utils.replaceUnicode4Digits(response); // Replace Unicode characters
			response = response.replaceAll("\\\\", "\\\\\\\\"); // Escape backward slashes
			response = response.replaceAll("\\\\\\\\\"", "\\\\\"");  // Unescape double quotes
			response = response.replaceAll("\\\\/", "/"); // Unescape forward slashes
			SSDCollection json = SSDF.readJSON(response);
			String content = json.getDirectString("content");
			Document document = Utils.parseDocument(content);
			Elements programs = document.select(".component--scope--cinematography > a");
			for(Element elProgram : programs) {
				// Ignore non-free programs
				if(elProgram.selectFirst(".component--scope--cinematography--picture--bottom--price") != null)
					continue;
				String url = Utils.urlFix(elProgram.attr("href"), true);
				String title = elProgram.attr("title");
				SSDCollection data = SSDF.readJSON(elProgram.attr("data-item-json"));
				String id = String.valueOf(data.getDirectInt("id"));
				Program program = new Program(Utils.uri(url), title, "source", iprima, "id", id, "type", graphQLType);
				if(!functionAddToList.apply(program)) return CALLBACK_EXIT; // Do not continue, if error
			}
			return programs.size();
		}
		
		private static final void getProgramsOfType(IPrima iprima, String subdomain, String type,
				CheckedFunction<Program, Boolean> functionAddToList, String graphQLType) throws Exception {
			String apiURL = Utils.format(URL_API, "subdomain", subdomain, "type", type);
			String response = internal_request(Utils.uri(apiURL));
			if(response != null) callback(iprima, response, type, functionAddToList, graphQLType);
		}
		
		public List<Program> getPrograms(IPrima iprima, String subdomain, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			List<Program> programs = new ArrayList<>();
			CheckedFunction<Program, Boolean> listAdder = ((program) -> {
				return internal_listAdder(proxy, function, programs, program);
			});
			getProgramsOfType(iprima, subdomain, TYPE_SHOW, listAdder, GRAPHQL_TYPE_SHOW);
			getProgramsOfType(iprima, subdomain, TYPE_MOVIE, listAdder, GRAPHQL_TYPE_MOVIE);
			return programs;
		}
	}
	
	static final class StaticProgramObtainer {
		
		private static final String SELECTOR_PROGRAM = ".programmes-list .w-full h2 > a";
		
		// Other
		private static StaticProgramObtainer INSTANCE;
		
		private StaticProgramObtainer() {
		}
		
		public static final StaticProgramObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new StaticProgramObtainer()) : INSTANCE;
		}
		
		public List<Program> getPrograms(IPrima iprima, String urlPrograms, WorkerProxy proxy,
				CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
			List<Program> programs = new ArrayList<>();
			CheckedFunction<Program, Boolean> listAdder = ((program) -> {
				return internal_listAdder(proxy, function, programs, program);
			});
			String response = internal_request(Utils.uri(urlPrograms));
			Document document = Utils.parseDocument(response, urlPrograms);
			for(Element elProgram : document.select(SELECTOR_PROGRAM)) {
				String url = elProgram.absUrl("href");
				String title = elProgram.text();
				Program program = new Program(Utils.uri(url), title, "source", iprima, "type", "ProgramNode");
				if(!listAdder.apply(program)) break; // Do not continue, if error or cancelled
			}
			return programs;
		}
	}
	
	static final class SnippetEpisodeObtainer {
		
		// General
		private static final String URL_API;
		
		// RegEx
		private static final Pattern REGEX_SNIPPETS;
		
		// Selectors
		private static final String SELECTOR_EPISODE = ".w-full h3 > a";
		
		// Other
		private static SnippetEpisodeObtainer INSTANCE;
		
		static {
			URL_API = "%{base_url}s/_snippet/%{type}s/%{count}d/%{offset}d/%{program_id}s";
			REGEX_SNIPPETS = Pattern.compile("new\\s+InfiniteCarousel\\([^,]+,\\s*'/_snippet/([^/]+)/[^']+/(\\d+)',[^\\)]+\\)", Pattern.DOTALL);
		}
		
		private SnippetEpisodeObtainer() {
		}
		
		public static final SnippetEpisodeObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new SnippetEpisodeObtainer()) : INSTANCE;
		}
		
		public List<Episode> getEpisodes(Program program, WorkerProxy proxy, CheckedBiFunction<WorkerProxy, Episode, Boolean> function)
				throws Exception {
			List<Episode> episodes = new ArrayList<>();
			CheckedFunction<Episode, Boolean> listAdder = ((episode) -> {
				return internal_listAdder(proxy, function, episodes, episode);
			});
			URI uri = program.uri();
			String response = internal_request(uri);
			if(response != null) {
				Document document = Utils.parseDocument(response, uri.toString());
				String baseURL = uri.getScheme() + "://" + uri.getHost();
				scriptsLoop:
				for(Element elScript : document.select("script")) {
					String scriptContent = elScript.html();
					Matcher matcher = REGEX_SNIPPETS.matcher(scriptContent);
					if(matcher.find()) {
						String snippetType = matcher.group(1);
						String programID = matcher.group(2);
						String urlSnippetAPI = Utils.format(URL_API, "base_url", baseURL, "type", snippetType,
							"count", Integer.MAX_VALUE, "offset", 0, "program_id", programID);
						response = internal_request(Utils.uri(urlSnippetAPI));
						if(response != null) {
							Document doc = Utils.parseDocument(response);
							for(Element elEpisode : doc.select(SELECTOR_EPISODE)) {
								String url = elEpisode.attr("href");
								String title = elEpisode.text();
								Episode episode = new Episode(program, Utils.uri(url), title);
								if(!listAdder.apply(episode)) break scriptsLoop; // Do not continue, if error or cancelled
							}
						}
					}
				}
			}
			return episodes;
		}
	}
	
	static final class PlayIDsMediaObtainer {
		
		// URLs
		private static final String URL_API_PLAY;
		
		// RegEx
		private static final Pattern REGEX_PLAY_IDS;
		private static final Pattern REGEX_NUM_EPISODE;
		
		// Selectors
		private static final String SELECTOR_SCRIPT = ".main-container > .content > header > .play-video > script";
		
		// Other
		private static PlayIDsMediaObtainer INSTANCE;
		
		static {
			URL_API_PLAY = "https://api.play-backend.iprima.cz/api/v1/products/play/ids-%{play_id}s";
			REGEX_PLAY_IDS = Pattern.compile("videos\\s*=\\s*'([^']+)';");
			REGEX_NUM_EPISODE = Pattern.compile("^.*?/([^/]+?)$");
		}
		
		private PlayIDsMediaObtainer() {
		}
		
		public static final PlayIDsMediaObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new PlayIDsMediaObtainer()) : INSTANCE;
		}
		
		public List<Media> getMedia(String url, WorkerProxy proxy, CheckedBiFunction<WorkerProxy, Media, Boolean> function,
				MediaEngine engine) throws Exception {
			List<Media> sources = new ArrayList<>();
			List<String> urls = new ArrayList<>();
			String response = internal_request(Utils.uri(url));
			if(response != null) {
				Document document = Utils.parseDocument(response);
				Element elScript = document.selectFirst(SELECTOR_SCRIPT);
				if(elScript != null) {
					String scriptContent = elScript.html();
					Matcher matcher = REGEX_PLAY_IDS.matcher(scriptContent);
					if(matcher.find()) {
						String[] playIDs = matcher.group(1).split(",");
						for(String playID : playIDs) {
							urls.add(Utils.format(URL_API_PLAY, "play_id", playID));
						}
					}
				}
			}
			if(!urls.isEmpty()) {
				// It is important to specify the referer, otherwise the response code is 403.
				Map<String, String> requestHeaders = Utils.toMap("Referer", "https://www.iprima.cz/");
				try {
					// Try to log in to the iPrima website using the internal account to have HD sources available.
					IPrimaAuthenticator.SessionData sessionData = IPrimaAuthenticator.getSessionData();
					Utils.merge(requestHeaders, sessionData.requestHeaders());
				} catch(Exception ex) {
					// Notify the user that the HD sources may not be available due to inability to log in.
					MediaDownloader.error(new IllegalStateException("Unable to log in to the iPrima website.", ex));
				}
				MediaSource source = MediaSource.of(engine);
				for(String urlPlay : urls) {
					String content = internal_request(Utils.uri(urlPlay), requestHeaders);
					if(content == null || content.isEmpty()) continue;
					SSDCollection data = SSDF.readJSON(content);
					URI sourceURI = Utils.uri(urlPlay);
					for(SSDCollection videoData : data.collectionsIterable()) {
						// Obtain the program title
						String programName = videoData.getString("productDetail.seriesTitle", "");
						// Obtain the season information
						String numSeason = videoData.getString("productDetail.seasonNumber", "0");
						numSeason = String.format("%02d", Integer.valueOf(numSeason));
						// Obtain the episode information
						String numEpisode = videoData.getString("productDetail.externalId", "0");
						Matcher matcher = REGEX_NUM_EPISODE.matcher(numEpisode);
						numEpisode = matcher.matches() ? matcher.group(1) : "0";
						numEpisode = String.format("%02d", Integer.valueOf(numEpisode));
						// Obtain the episode title
						String episodeName = videoData.getString("productDetail.episodeTitle", "");
						// Finally, construct the whole title
						String title = MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
						// Add all the media
						SSDCollection streamInfos = videoData.getDirectCollection("streamInfos");
						for(SSDCollection streamInfo : streamInfos.collectionsIterable()) {
							String src = streamInfo.getDirectString("url");
							MediaLanguage language = MediaLanguage.ofCode(streamInfo.getString("lang.key"));
							List<Media> media = MediaUtils.createMedia(source, Utils.uri(src), sourceURI, title,
								language, MediaMetadata.empty());
							for(Media s : media) {
								sources.add(s);
								if(!function.apply(proxy, s))
									return null; // Do not continue
							}
						}
					}
				}
			}
			return sources;
		}
	}
	
	private static abstract class Wrapper<T> implements Comparable<Wrapper<T>> {
		
		protected final T value;
		
		public Wrapper(T value) {
			this.value = Objects.requireNonNull(value);
		}
		
		public T value() {
			return value;
		}
	}
	
	private static final class EpisodeWrapper extends Wrapper<Episode> {
		
		private final int offset;
		private final int index;
		
		public EpisodeWrapper(Episode episode, int offset, int index) {
			super(episode);
			this.offset = offset;
			this.index = index;
		}
		
		// Utility function
		private static final EpisodeWrapper cast(Wrapper<Episode> wrapper) {
			return (EpisodeWrapper) wrapper;
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(value);
		}
		
		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(obj == null)
				return false;
			if(getClass() != obj.getClass())
				return false;
			EpisodeWrapper other = (EpisodeWrapper) obj;
			// Do not compare program data
			return Objects.equals(value.uri(), other.value.uri())
						&& Objects.equals(value.title().toLowerCase(), other.value.title().toLowerCase());
		}
		
		@Override
		public int compareTo(Wrapper<Episode> w) {
			if(Objects.requireNonNull(w) == this) return 0;
			return Comparator.<Wrapper<Episode>, Integer>comparing((e) -> cast(e).offset)
						.thenComparing((e) -> cast(e).index)
						.thenComparing((a, b) -> compareNatural(cast(a).value.title().toLowerCase(),
						                                        cast(b).value.title().toLowerCase()))
						.compare(this, w);
		}
	}
	
	private static abstract class ThreadedSpawnableTaskQueue<P, R> {
		
		private final ExecutorService executor;
		private final AtomicReference<Exception> exception = new AtomicReference<>();
		
		public ThreadedSpawnableTaskQueue(int maxThreads) {
			this.executor = Threads.Pools.newWorkStealing(maxThreads);
		}
		
		private final Callable<R> newTask(P arg) {
			return (() -> {
				boolean shouldShutdown = false;
				try {
					R val = runTask(arg);
					shouldShutdown = shouldShutdown(val);
					return val;
				} catch(Exception ex) {
					shouldShutdown = exception.compareAndSet(null, ex);
					throw ex;
				} finally {
					if(shouldShutdown) {
						executor.shutdownNow();
					}
				}
			});
		}
		
		protected abstract R runTask(P arg) throws Exception;
		protected abstract boolean shouldShutdown(R val);
		
		public final ThreadedSpawnableTaskQueue<P, R> addTask(P arg) {
			executor.submit(newTask(arg));
			return this;
		}
		
		public final void await() throws Exception {
			executor.shutdown();
			executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
			
			Exception ex;
			if((ex = exception.get()) != null)
				throw ex;
		}
	}
	
	private static final class ConcurrentVarLazyLoader<T> {
		
		private final AtomicBoolean isSet = new AtomicBoolean();
		private final AtomicBoolean isSetting = new AtomicBoolean();
		private volatile T value;
		
		private final CheckedSupplier<T> supplier;
		
		private ConcurrentVarLazyLoader(CheckedSupplier<T> supplier) {
			this.supplier = Objects.requireNonNull(supplier);
		}
		
		public static final <T> ConcurrentVarLazyLoader<T> of(CheckedSupplier<T> supplier) {
			return new ConcurrentVarLazyLoader<>(supplier);
		}
		
		public final T value() throws Exception {
			if(isSet.get()) return value; // Already set
			
			while(!isSet.get()
						&& !isSetting.compareAndSet(false, true)) {
				synchronized(isSetting) {
					try {
						isSetting.wait();
					} catch(InterruptedException ex) {
						// Ignore
					}
				}
				if(isSet.get()) return value; // Already set
			}
			
			try {
				value = supplier.get();
				isSet.set(true);
				return value;
			} finally {
				isSetting.set(false);
				synchronized(isSetting) {
					isSetting.notifyAll();
				}
			}
		}
	}
	
	@FunctionalInterface
	protected static interface CheckedPentaFunction<A, B, C, D, E, R> {
		
		R apply(A a, B b, C c, D d, E e) throws Exception;
		default <V> CheckedPentaFunction<A, B, C, D, E, V> andThen(CheckedFunction<? super R, ? extends V> after) {
			Objects.requireNonNull(after);
			return (A a, B b, C c, D d, E e) -> after.apply(apply(a, b, c, d, e));
		}
	}
}