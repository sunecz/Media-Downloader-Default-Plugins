package sune.app.mediadown.media_engine.ceskatelevize;

import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaContainer;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Net.QueryArgument;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.CheckedSupplier;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;

public final class CeskaTelevizeEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	private static final CT[] SUPPORTED_WEBS = {
		CT_iVysilani.INSTANCE,
		CT_Decko.INSTANCE,
		CT_24.INSTANCE,
		CT_Sport.INSTANCE,
		CT_Art.INSTANCE,
		CT_Edu.INSTANCE,
	};
	
	// Allow to create an instance when registering the engine
	CeskaTelevizeEngine() {
	}
	
	private static final boolean checkURLSubdomain(URI uri, String required) {
		String[] hostParts = uri.getHost().split("\\.", 2);
		return hostParts.length > 1 && hostParts[0].equalsIgnoreCase(required);
	}
	
	private static final String randomUUID() {
		return UUID.randomUUID().toString();
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> {
			(new IntConcurrentLoop() {
				
				@Override
				protected void iteration(Integer category) throws Exception {
					ListTask<Program> t = API.getPrograms(category);
					t.forwardAdd(task);
					t.startAndWait();
				}
			}).iterate(API.categories());
		});
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return ListTask.of((task) -> {
			// We need to get the IDEC of the given program first
			WebMediaMetadata metadata = Common.retry(
				() -> WebMediaMetadataExtractor.extract(HTML.from(program.uri()), true),
				Objects::nonNull
			);
			API.getEpisodes(task, program, metadata);
		});
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			CT ct = Arrays.stream(SUPPORTED_WEBS)
				.filter((c) -> c.isCompatible(uri))
				.findFirst().orElse(null);
			
			if(ct == null) {
				return; // Not supported
			}
			
			List<ExtractJob> jobs = new ArrayList<>();
			MediaSource source = MediaSource.of(this);
			ct.extractJobs(uri, HTML.from(uri), jobs);
			
			for(ExtractJob job : jobs) {
				job.execute(source, task);
			}
		});
	}
	
	@Override
	public boolean isDirectMediaSupported() {
		return true;
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
	
	private static final class Common {
		
		private static final int MAX_RETRY_ATTEMPTS = 5;
		
		private Common() {}
		
		public static final <T> T retry(CheckedSupplier<T> action) throws Exception {
			return retry(action, (v) -> true, MAX_RETRY_ATTEMPTS);
		}
		
		public static final <T> T retry(CheckedSupplier<T> action, Predicate<T> isValid) throws Exception {
			return retry(action, isValid, MAX_RETRY_ATTEMPTS);
		}
		
		public static final <T> T retry(CheckedSupplier<T> action, Predicate<T> isValid, int maxNumOfAttempts)
				throws Exception {
			for(int attempt = 1;; ++attempt) {
				try {
					T value = action.get();
					
					if(isValid.test(value)) {
						return value;
					}
					
					if(attempt >= maxNumOfAttempts) {
						throw new IllegalStateException("Invalid value");
					}
				} catch(TimeoutException ex) {
					if(attempt >= maxNumOfAttempts) {
						throw ex; // Rethrow
					}
				} catch(ExecutionException ex) {
					if(attempt >= maxNumOfAttempts
							|| !(ex.getCause() instanceof HttpConnectTimeoutException)) {
						throw ex; // Rethrow
					}
				}
			}
		}
	}
	
	private static final class API {
		
		private static final String URL = "https://api.ceskatelevize.cz/graphql/";
		private static final String REFERER = "https://www.ceskatelevize.cz/";
		private static final String URL_BASE_PROGRAM = "https://www.ceskatelevize.cz/porady/";
		
		private static final int CATEGORY_ALL_FILMS = 3947;
		private static final int CATEGORY_ALL_SERIES = 3976;
		private static final int CATEGORY_ALL_DOCUMENTS = 4003;
		private static final int CATEGORY_ALL_NEWS = 4124;
		private static final int CATEGORY_ALL_CULTURE = 4029;
		private static final int CATEGORY_ALL_FUN = 4068;
		private static final int CATEGORY_ALL_NATURE = 4106;
		private static final int CATEGORY_ALL_HISTORY = 4079;
		private static final int CATEGORY_ALL_TIPS_AND_RECIPES = 4055;
		private static final int CATEGORY_ALL_SOCIETY = 4093;
		private static final int CATEGORY_ALL_KIDS = 4118;
		private static final int CATEGORY_ALL_SPORT = 4142;
		private static final int CATEGORY_ALL_SPIRITUALITY = 4191;
		
		private static final int[] CATEGORIES = new int[] {
			CATEGORY_ALL_FILMS, CATEGORY_ALL_SERIES, CATEGORY_ALL_DOCUMENTS, CATEGORY_ALL_NEWS,
			CATEGORY_ALL_CULTURE, CATEGORY_ALL_FUN, CATEGORY_ALL_NATURE, CATEGORY_ALL_HISTORY,
			CATEGORY_ALL_TIPS_AND_RECIPES, CATEGORY_ALL_SOCIETY, CATEGORY_ALL_KIDS, CATEGORY_ALL_SPORT,
			CATEGORY_ALL_SPIRITUALITY
		};
		
		private static final String QUERY_GET_PROGRAMS_BY_CATEGORY = ""
				+ "query GetCategoryById("
				+ "  $limit: PaginationAmount!,"
				+ "  $offset: Int!, $categoryId: String!,"
				+ "  $order: OrderByDirection,"
				+ "  $orderBy: CategoryOrderByType"
				+ ") {\n"
				+ "  showFindByGenre(\n"
				+ "    limit: $limit\n"
				+ "    offset: $offset\n"
				+ "    categoryId: $categoryId\n"
				+ "    order: $order\n"
				+ "    orderBy: $orderBy\n"
				+ "  ) {\n"
				+ "    items {\n"
				+ "      ...ShowCardFragment\n"
				+ "      __typename\n"
				+ "    }\n"
				+ "    totalCount\n"
				+ "    __typename\n"
				+ "  }\n"
				+ "}\n"
				+ "\n"
				+ "fragment ShowCardFragment on Show {\n"
				+ "  id\n"
				+ "  slug\n"
				+ "  title\n"
				+ "  __typename\n"
				+ "}\n";
		
		private static final String QUERY_GET_EPISODES = ""
				+ "query GetEpisodes("
				+ "  $idec: String!,"
				+ "  $seasonId: String,"
				+ "  $limit: PaginationAmount!,"
				+ "  $offset: Int!,"
				+ "  $orderBy: EpisodeOrderByType!,"
				+ "  $keyword: String"
				+ ") {\n"
				+ "  episodesPreviewFind(\n"
				+ "    idec: $idec\n"
				+ "    seasonId: $seasonId\n"
				+ "    limit: $limit\n"
				+ "    offset: $offset\n"
				+ "    orderBy: $orderBy\n"
				+ "    keyword: $keyword\n"
				+ "  ) {\n"
				+ "    totalCount\n"
				+ "    items {\n"
				+ "      ...VideoCardFragment\n"
				+ "      __typename\n"
				+ "    }\n"
				+ "    __typename\n"
				+ "  }\n"
				+ "}\n"
				+ "\n"
				+ "fragment VideoCardFragment on EpisodePreview {\n"
				+ "  id\n"
				+ "  playable\n"
				+ "  title\n"
				+ "  __typename\n"
				+ "}\n";
		
		private static final String QUERY_SEARCH_SHOWS = ""
				+ "query SearchShows("
				+ "  $limit: PaginationAmount!,"
				+ "  $offset: Int!,"
				+ "  $search: String!,"
				+ "  $onlyPlayable: Boolean"
				+ ") {\\n"
				+ "  searchShows(\\n"
				+ "    limit: $limit\\n"
				+ "    offset: $offset\\n"
				+ "    keyword: $search\\n"
				+ "    onlyPlayable: $onlyPlayable\\n"
				+ "  ) {\\n"
				+ "    totalCount\\n"
				+ "    items {\\n"
				+ "      code\\n"
				+ "      id\\n"
				+ "      __typename\\n"
				+ "    }\\n"
				+ "    __typename\\n"
				+ "  }\\n"
				+ "}\\n";
		
		// API requests with 'limit' above 40 return "400 Bad request".
		public static final int MAX_ITEMS_PER_PAGE = 40;
		
		private static final String programSlugToURL(String slug) {
			return URL_BASE_PROGRAM + slug + "/";
		}
		
		private static final String episodeSlugToURL(Program program, String id) {
			return program.uri() + id + "/";
		}
		
		private static final Program parseProgram(JSONCollection data) {
			String id = data.getString("id");
			String url = programSlugToURL(data.getString("slug"));
			String title = data.getString("title");
			return new Program(Net.uri(url), title, "id", id);
		}
		
		private static final Episode parseEpisode(Program program, JSONCollection data, int episodeIndex,
				int seasonIndex) {
			String id = data.getString("id");
			URI url = Net.uri(episodeSlugToURL(program, id));
			String title = data.getString("title");
			int numEpisode = episodeIndex + 1;
			int numSeason = seasonIndex + 1;
			boolean playable = data.getBoolean("playable");
			return new Episode(
				program, url, title, numEpisode, numSeason, new Object[] { "id", id, "playable", playable }
			);
		}
		
		private static final String createRequestBody(String operationName, String query, Object... args) {
			if((args.length & 1) != 0)
				throw new IllegalArgumentException("Arguments length must be even.");
			JSONCollection json = JSONCollection.empty();
			json.set("operationName", operationName);
			json.set("query", query);
			JSONCollection vars = JSONCollection.empty();
			for(int i = 0, l = args.length; i < l; i += 2) {
				String name = (String) args[i];
				Object value = args[i + 1];
				if(value != null) { // Do not permit null values
					vars.set(name, JSONObject.of(value));
				}
			}
			json.set("variables", vars);
			return json.toString(true).replaceAll("\\n", "\\\\n");
		}
		
		private static final JSONCollection doOperation(String operationName, String query, Object... variables)
				throws Exception {
			String body = createRequestBody(operationName, query, variables);
			String contentType = "application/json";
			HttpHeaders headers = Web.Headers.ofSingle("Referer", REFERER);
			try(Response.OfStream response = Web.requestStream(
					Request.of(Net.uri(URL)).headers(headers).POST(body, contentType)
			)) {
				if(response.statusCode() != 200) {
					throw new IllegalStateException(
						"API returned non-OK code: " + response.statusCode() + ".\n" +
						"Body: " + Utils.streamToString(response.stream())
					);
				}
				
				return JSON.read(response.stream());
			}
		}
		
		public static final CollectionAPIResult getProgramsWithCategory(int categoryId, int offset, int length)
				throws Exception {
			JSONCollection json = doOperation("GetCategoryById", QUERY_GET_PROGRAMS_BY_CATEGORY,
				"categoryId", String.valueOf(categoryId),
				"limit", length,
				"offset", offset,
				"order", "asc",
				"orderBy", "alphabet");
			JSONCollection items = json.getCollection("data.showFindByGenre.items");
			int total = json.getInt("data.showFindByGenre.totalCount");
			return new CollectionAPIResult(items, total);
		}
		
		public static final CollectionAPIResult getEpisodes(String idec, int offset, int length, String seasonId)
				throws Exception {
			JSONCollection json = doOperation("GetEpisodes", QUERY_GET_EPISODES,
				"idec", idec,
				"limit", length,
				"offset", offset,
				"orderBy", "oldest",
				"seasonId", seasonId);
			JSONCollection items = json.getCollection("data.episodesPreviewFind.items");
			int total = json.getInt("data.episodesPreviewFind.totalCount");
			return new CollectionAPIResult(items, total);
		}
		
		public static final ListTask<Program> getPrograms(int categoryId) throws Exception {
			return ListTask.of((task) -> {
				int offset = 0, total = -1;
				CollectionAPIResult result;
				
				loop:
				do {
					final int off = offset;
					result = Common.retry(
						()  -> getProgramsWithCategory(categoryId, off, MAX_ITEMS_PER_PAGE),
						(v) -> v.items() != null
					);
					
					for(JSONCollection item : result.items().collectionsIterable()) {
						Program program = parseProgram(item);
						
						if(!task.add(program)) {
							break loop;
						}
					}
					
					if(total < 0) {
						total = result.total();
					}
					
					offset += MAX_ITEMS_PER_PAGE;
				} while(offset < total);
			});
		}
		
		public static final void getEpisodes(ListTask<Episode> task, Program program, WebMediaMetadata metadata)
				throws Exception {
			String idec = metadata.idec();
			List<String> seasons = metadata.seasons();
			int seasonIndex = 0;
			CollectionAPIResult result;
			Set<URI> visited = null;
			
			if(!seasons.isEmpty()) {
				// Only check episodes for the "All episodes" season when there
				// are other seasons.
				visited = new HashSet<>();
			}
			
			// Always check the "All episodes" season
			seasons.add(null);
			
			for(String seasonId : seasons) {
				int offset = 0, total = -1;
				int episodeIndex = 0;
				
				loop:
				do {
					final int off = offset;
					result = Common.retry(
						()  -> getEpisodes(idec, off, MAX_ITEMS_PER_PAGE, seasonId),
						(v) -> v.items() != null
					);
					
					// Always use the "No season" season for the "All episodes" season.
					int alteredSeasonIndex = seasonId == null ? -1 : seasonIndex;
					
					for(JSONCollection item : result.items().collectionsIterable()) {
						Episode episode = parseEpisode(program, item, episodeIndex++, alteredSeasonIndex);
						
						if(visited != null && !visited.add(episode.uri())) {
							continue; // Episode already visited
						}
						
						if(!task.add(episode)) {
							break loop;
						}
					}
					
					if(total < 0) {
						total = result.total();
					}
					
					offset += MAX_ITEMS_PER_PAGE;
				} while(offset < total);
				
				++seasonIndex;
			}
		}
		
		public static final CollectionAPIResult searchShows(String text, int offset, int limit) throws Exception {
			JSONCollection json = doOperation(
				"SearchShows", QUERY_SEARCH_SHOWS,
				"limit", limit,
				"offset", offset,
				"search", text,
				"onlyPlayable", false
			);
			JSONCollection items = json.getCollection("data.searchShows.items");
			int total = json.getInt("data.searchShows.totalCount");
			return new CollectionAPIResult(items, total);
		}
		
		public static final String getShowId(String showCode) throws Exception {
			CollectionAPIResult result = Common.retry(
				()  -> searchShows(showCode, 0, 1),
				(v) -> v.items() != null
			);
			
			return Opt.of(Utils.stream(result.items().collectionsIterable())
			                   .filter((c) -> c.getString("code").equals(showCode))
			                   .findFirst())
					  .<Optional<JSONCollection>>castAny()
					  .ifTrue(Optional::isPresent)
					  .map((o) -> o.get().getString("id"))
					  .orElse(null);
		}
		
		public static final int[] categories() {
			return CATEGORIES;
		}
		
		static final class CollectionAPIResult {
			
			private final JSONCollection items;
			private final int total;
			
			public CollectionAPIResult(JSONCollection items, int total) {
				this.items = items;
				this.total = total;
			}
			
			public JSONCollection items() {
				return items;
			}
			
			public int total() {
				return total;
			}
		}
	}
	
	private static abstract class ConcurrentLoop<T> {
		
		protected final ExecutorService executor = Threads.Pools.newWorkStealing();
		
		protected abstract void iteration(T value) throws Exception;
		
		protected final void await() throws Exception {
			executor.shutdown();
			// Do not throw the InterruptedException (i.e. when cancelled the loop, etc.)
			Ignore.callVoid(() -> executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS));
		}
		
		protected final void submit(T value) {
			executor.submit(Utils.callable(() -> iteration(value)));
		}
		
		@SuppressWarnings({ "unchecked", "unused" })
		public void iterate(T... values) throws Exception {
			for(T value : values)
				submit(value);
			await();
		}
	}
	
	private static abstract class IntConcurrentLoop extends ConcurrentLoop<Integer> {
		
		public void iterate(int... values) throws Exception {
			for(int value : values)
				submit(value);
			await();
		}
	}
	
	private static interface ExtractJob {
		
		void execute(MediaSource source, ListTask<Media> task) throws Exception;
	}
	
	private static interface SourceInfo {
		
		URI playlistUri();
	}
	
	private static final class SourceInfoExtractJob implements ExtractJob {
		
		private final URI uri;
		private final SourceInfo source;
		private final String title;
		
		public SourceInfoExtractJob(URI uri, SourceInfo source, String title) {
			this.uri = Objects.requireNonNull(uri);
			this.source = Objects.requireNonNull(source);
			this.title = title; // Optional title
		}
		
		@Override
		public void execute(MediaSource source, ListTask<Media> task) throws Exception {
			VOD.Playlist playlist = Common.retry(() -> VOD.INSTANCE.ofSource(this.source));
			if(playlist == null) return; // Unsuccessful
			
			String title;
			if((title = this.title) == null) {
				title = playlist.title();
			}
			
			for(VOD.Playlist.Stream stream : playlist.streams()) {
				URI finalUri;
				
				try(Response response = Web.peek(Request.of(stream.uri()).HEAD())) {
					finalUri = response.uri();
				}
				
				List<Media.Builder<?, ?>> media = MediaUtils.createMediaBuilders(
					source, finalUri, uri, title, MediaLanguage.UNKNOWN, MediaMetadata.empty()
				);
				
				for(Entry<MediaLanguage, List<URI>> entry : stream.subtitles().entrySet()) {
					MediaLanguage language = entry.getKey();
					
					for(URI subtitleUri : entry.getValue()) {
						if(!subtitleUri.getPath().endsWith("vtt")) {
							continue;
						}
						
						MediaFormat format = MediaFormat.VTT;
						SubtitlesMedia.Builder<?, ?> subtitles = SubtitlesMedia.simple()
							.source(source)
							.uri(subtitleUri)
							.format(format)
							.language(language);
						
						media.forEach((m) -> ((MediaContainer.Builder<?, ?>) m).addMedia(subtitles));
						break; // Keep only VTT for now
					}
				}
				
				// Finally, add all the media
				for(Media s : Utils.iterable(media.stream().map(Media.Builder::build).iterator())) {
					if(!task.add(s)) {
						return; // Do not continue
					}
				}
			}
		}
	}
	
	private static final class WebMediaMetadata {
		
		private final String idec;
		private final List<String> seasons;
		
		public WebMediaMetadata(String idec, List<String> seasons) {
			this.idec = idec;
			this.seasons = seasons;
		}
		
		public String idec() {
			return idec;
		}
		
		public List<String> seasons() {
			return seasons;
		}
	}
	
	private static final class WebMediaMetadataExtractor {
		
		private static final String SELECTOR_SCRIPT = "script#__NEXT_DATA__";
		private static final Regex PATTERN_IDEC = Regex.of("\"idec\":\"(?<idec>[^\"]+)\"");
		
		public static final WebMediaMetadata extract(Document document, boolean extractSeasons) {
			Element elScript = document.selectFirst(SELECTOR_SCRIPT);
			
			if(elScript == null) {
				return null; // No metadata script content available
			}
			
			String content = elScript.html();
			Matcher matcher = PATTERN_IDEC.matcher(content);
			
			if(!matcher.find()) {
				return null; // Content does not contain the needed metadata
			}
			
			List<String> seasons = null;
			
			if(extractSeasons) {
				JSONCollection jsonSeasons = JSON.read(content).getCollection("props.pageProps.data.show.seasons");
				seasons = new ArrayList<>();
				
				for(JSONCollection item : jsonSeasons.collectionsIterable()) {
					seasons.add(item.getString("id"));
				}
			}
			
			return new WebMediaMetadata(matcher.group("idec"), seasons);
		}
		
		// Forbid anyone to create an instance of this class
		private WebMediaMetadataExtractor() {
		}
	}
	
	// Note: Based on https://player.ceskatelevize.cz/_next/static/chunks/695-cede098ec19ef364.js
	private static enum VOD {
		INSTANCE;
		
		private static final URI ENDPOINT;
		private static final String PATH_EXTERNAL;
		private static final String PATH_INTERNAL;
		private static final String PATH_BONUS;
		
		private static final Regex REGEX_NUM_EPISODE = Regex.of("\\s*\\|\\s*(\\d+)$");
		private static final String CLIENT_VERSION = "0.37.1";
		
		static {
			ENDPOINT = Net.uri("https://api.ceskatelevize.cz/video/v1/playlist-vod/v1/");
			PATH_EXTERNAL = "stream-data/media"
				+ "/external"
				+ "/%{idec}s"
				+ "?canPlayDrm=true"
				+ "&quality=web"
				+ "&streamType=dash"
				+ "&deviceId=%{deviceId}s"
				+ "&origin=%{origin}s"
				+ "&client=%{client}s"
				+ "&clientVersion=" + CLIENT_VERSION;
			PATH_INTERNAL = "stream-data/version"
				+ "/%{versionId}s"
				+ "?canPlayDrm=true"
				+ "&quality=web"
				+ "&streamType=dash"
				+ "&sessionId=%{sessionId}s"
				+ "&origin=%{origin}s"
				+ "&client=%{client}s"
				+ "&clientVersion=" + CLIENT_VERSION;
			PATH_BONUS = "stream-data/bonus"
				+ "/BO-%{value}s"
				+ "?canPlayDrm=true"
				+ "&quality=web"
				+ "&streamType=dash"
				+ "&deviceId=%{deviceId}s"
				+ "&origin=%{origin}s"
				+ "&client=%{client}s"
				+ "&clientVersion=" + CLIENT_VERSION;
		}
		
		private static final Playlist.Stream parseStream(JSONCollection collection) {
			URI uri = Net.uri(collection.getString("url"));
			
			if(!collection.hasCollection("subtitles")) {
				return new Playlist.Stream(uri, Map.of());
			}
			
			Map<MediaLanguage, List<URI>> subtitles = new LinkedHashMap<>();
			
			for(JSONCollection item : collection.getCollection("subtitles").collectionsIterable()) {
				MediaLanguage language = MediaLanguage.ofCode(item.getString("language"));
				List<URI> uris = new ArrayList<>();
				
				for(JSONCollection file : item.getCollection("files").collectionsIterable()) {
					uris.add(Net.uri(file.getString("url")));
				}
				
				subtitles.put(language, uris);
			}
			
			return new Playlist.Stream(uri, subtitles);
		}
		
		private static final Playlist parsePlaylist(URI uri) throws Exception {
			List<Playlist.Stream> streams = new ArrayList<>();
			String title = null;
			
			try(Response.OfStream response = Web.requestStream(Request.of(uri).GET())) {
				JSONCollection json = JSON.read(response.stream());
				
				if(json.has("error") || json.has("message")) {
					return null; // Do not throw exception
				}
				
				for(JSONCollection item : json.getCollection("streams").collectionsIterable()) {
					Playlist.Stream stream = parseStream(item);
					
					if(stream == null) {
						continue;
					}
					
					streams.add(stream);
				}
				
				String programTitle = json.getString("showTitle");
				String episodeTitle = json.getString("episodeTitle");
				
				if(programTitle != null && episodeTitle != null) {
					String numEpisode = null;
					Matcher matcher = REGEX_NUM_EPISODE.matcher(episodeTitle);
					
					// If there is an episode number in the suffix of the episode title,
					// extract it and remove it from the episode title.
					if(matcher.find()) {
						numEpisode = matcher.group(1);
						episodeTitle = episodeTitle.substring(0, matcher.start());
					}
					
					title = MediaUtils.mediaTitle(programTitle, null, numEpisode, episodeTitle);
				} else {
					title = json.getString("title");
				}
			}
			
			return new Playlist(streams, title);
		}
		
		public Playlist ofSource(SourceInfo source) throws Exception {
			return parsePlaylist(source.playlistUri());
		}
		
		private static abstract class SourceInfoBase implements SourceInfo {
			
			protected final String origin;
			protected final String client;
			
			public SourceInfoBase(String origin, String client) {
				this.origin = Objects.requireNonNull(origin);
				this.client = Objects.requireNonNull(client);
			}
		}
		
		protected static final class ExternalSourceInfo extends SourceInfoBase {
			
			private final String idec;
			private final String deviceId;
			
			public ExternalSourceInfo(String idec, String deviceId, String origin, String client) {
				super(origin, client);
				this.idec = Objects.requireNonNull(idec);
				this.deviceId = Objects.requireNonNull(deviceId);
			}
			
			@Override
			public URI playlistUri() {
				String path = Utils.format(
					PATH_EXTERNAL,
					"idec", idec,
					"deviceId", deviceId,
					"origin", origin,
					"client", client
				);
				
				return Net.resolve(ENDPOINT, path);
			}
		}
		
		protected static final class InternalSourceInfo extends SourceInfoBase {
			
			private final String versionId;
			private final String sessionId;
			
			private InternalSourceInfo(String versionId, String sessionId, String origin, String client) {
				super(origin, client);
				this.versionId = Objects.requireNonNull(versionId);
				this.sessionId = Objects.requireNonNull(sessionId);
			}
			
			@Override
			public URI playlistUri() {
				String path = Utils.format(
					PATH_INTERNAL,
					"versionId", versionId,
					"sessionId", sessionId,
					"origin", origin,
					"client", client
				);
				
				return Net.resolve(ENDPOINT, path);
			}
		}
		
		protected static final class BonusSourceInfo extends SourceInfoBase {
			
			private final String value;
			private final String deviceId;
			
			public BonusSourceInfo(String value, String deviceId, String origin, String client) {
				super(origin, client);
				this.value = Objects.requireNonNull(value);
				this.deviceId = Objects.requireNonNull(deviceId);
			}
			
			@Override
			public URI playlistUri() {
				String path = Utils.format(
					PATH_BONUS,
					"value", value,
					"deviceId", deviceId,
					"origin", origin,
					"client", client
				);
				
				return Net.resolve(ENDPOINT, path);
			}
		}
		
		protected static final class Playlist {
			
			private final List<Stream> streams;
			private final String title;
			
			private Playlist(List<Stream> streams, String title) {
				this.streams = Objects.requireNonNull(streams);
				this.title = title; // Optional title
			}
			
			public List<Stream> streams() { return streams; }
			public String title() { return title; }
			
			public static final class Stream {
				
				private final URI uri;
				private final Map<MediaLanguage, List<URI>> subtitles;
				
				private Stream(URI uri, Map<MediaLanguage, List<URI>> subtitles) {
					this.uri = Objects.requireNonNull(uri);
					this.subtitles = Objects.requireNonNull(subtitles);
				}
				
				public URI uri() { return uri; }
				public Map<MediaLanguage, List<URI>> subtitles() { return subtitles; }
			}
		}
	}
	
	private static final class MediaTitle {
		
		private static final Regex REGEX_SEASON = Regex.of("^(\\d+|[IVXLCDM]+)(\\.\\s+.*)?$");
		private static final Regex REGEX_EPISODE = Regex.of("^(?:Epizoda\\s+)?([\\d\\s\\+]+)/\\d+(?:\\s+(.*))?$");
		
		private MediaTitle() {}
		
		private static final String ofMedia(NextJS nextJS, String defaultValue) throws Exception {
			JSONCollection meta = nextJS.collectionOf("MediumMeta");
			
			if(meta == null) {
				return defaultValue;
			}
			
			String programName = meta.getString("show.title", "");
			String episodeName = meta.getString("title", "");
			String numSeason = "";
			String numEpisode = "";
			
			// Try to obtain the episode number
			Matcher matcherEpisode = REGEX_EPISODE.matcher(episodeName);
			if(matcherEpisode.matches()) {
				String numString = matcherEpisode.group(1);
				if(numString.matches("\\d+")) numEpisode = String.format("%02d", Integer.valueOf(numString));
				else numEpisode = Stream.of(Regex.of("\\s*\\+\\s*").split(numString))
						                .map((n) -> String.format("%02d", Integer.valueOf(n)))
						                .reduce(null, (a, b) -> (a != null ? a + "-" : "") + b);
				episodeName = matcherEpisode.group(2);
			}
			
			JSONCollection seasons = meta.getCollection("show.seasons", null);
			
			// Try to obtain the season number
			if(seasons != null) {
				String activeSeasonId = meta.getString("activeSeasonId", null);
				
				if(activeSeasonId == null || activeSeasonId.equals("null")) {
					// Some episodes can be visible only on the All episodes page. No season
					// can therefore be found so just use some invalid value.
					numSeason = "";
				} else {
					String textSeason = Utils.stream(seasons.collectionsIterable())
							.filter((c) -> c.getString("id", "").equals(activeSeasonId))
							.map((c) -> c.getString("title", ""))
							.findFirst().orElse(null);
					
					if(textSeason != null) {
						Matcher matcherSeason = REGEX_SEASON.matcher(textSeason);
						
						if(matcherSeason.matches()) {
							int num; String numString = matcherSeason.group(1);
							if(numString.matches("\\d+")) num = Integer.valueOf(numString);
							else num = Utils.romanToInteger(numString);
							numSeason = String.format("%02d", num);
						}
					}
				}
				
				// Try to obtain the episode number within the season, if the first attempt failed
				if(numEpisode.isBlank()) {
					String seasonId = "null".equals(activeSeasonId) ? null : activeSeasonId;
					String episodeId = meta.getString("id");
					String showIDEC = meta.getString("show.idec");
					int num = Episodes.indexOf(episodeId, showIDEC, seasonId);
					if(num != -1) numEpisode = String.format("%02d", num + 1);
				}
			}
			
			// Hotfix: Empty season and/or episode should not be shown in the title, therefore
			//         we currently have to set it to null, since it is not handled correctly
			//         in the program's builtin media title formats.
			numSeason  = numSeason  != null && !numSeason .isEmpty() ? numSeason  : null;
			numEpisode = numEpisode != null && !numEpisode.isEmpty() ? numEpisode : null;
			// Hotfix: Episode name cannot be null, since it is not handled correctly.
			episodeName = episodeName != null ? episodeName : "";
			
			return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName, false);
		}
	}
	
	private static final class Episodes {
		
		private static Map<String, List<String>> cache;
		
		private Episodes() {}
		
		private static final String programSeasonKey(String programIDEC, String seasonId) {
			return programIDEC + ':' + seasonId;
		}
		
		public static final int indexOf(String episodeId, String programIDEC, String seasonId) throws Exception {
			int offset = 0, total = -1, ctr = 0, index = -1;
			
			// Only create the cache if needed
			if(cache == null) {
				cache = new HashMap<>();
			}
			
			String key = programSeasonKey(programIDEC, seasonId);
			List<String> episodes = cache.computeIfAbsent(key, (k) -> new ArrayList<>());
			
			// Look through cached episodes first
			if(!episodes.isEmpty()) {
				int i = episodes.indexOf(episodeId);
				if(i != -1) return i;
				offset = episodes.size();
			}
			
			// Iteratively get as many episodes as needed in chunks and find the needed one
			API.CollectionAPIResult result;
			do {
				final int off = offset;
				result = Common.retry(
					()  -> API.getEpisodes(programIDEC, off, API.MAX_ITEMS_PER_PAGE, seasonId),
					(v) -> v.items() != null
				);
				
				JSONCollection items = result.items();
				ctr = 0;
				
				for(JSONCollection item : items.collectionsIterable()) {
					String id = item.getString("id", "");
					episodes.add(id); // Put to the cache
					if(id.equals(episodeId)) index = ctr; // Do not break from the loop due to caching
					++ctr;
				}
				
				if(total < 0) {
					total = result.total();
				}
				
				offset += API.MAX_ITEMS_PER_PAGE;
			} while(index < 0 && offset < total);
			
			return index;
		}
	}
	
	private static final class NextJS {
		
		private static final int FLAG_ESCAPED = 1 << 0;
		private static final int FLAG_QUOTED_SINGLE = 1 << 1;
		private static final int FLAG_QUOTED_DOUBLE = 1 << 2;
		private static final int FLAG_QUOTED = FLAG_QUOTED_SINGLE | FLAG_QUOTED_DOUBLE;
		private static final Regex REGEX_OBJECT_NAME = Regex.of("(?:\"([^\"]+)\":[{\\[]|\\[\\{|,[{\\[])$");
		
		private final String json;
		
		private NextJS(String json) {
			this.json = Objects.requireNonNull(json);
		}
		
		// Assuming the start position is not inside a quoted string.
		private static final int startOfCollection(String string, int start) {
			for(int i = start, countObject = 0, countArray = 0, flags = 0, c; i >= 0; --i) {
				switch(c = string.charAt(i)) {
					case '\"':
					case '\'': {
						int run = 0;
						for(; i - 1 >= 0 && string.charAt(i - 1) == '\\'; ++run, --i);
						if((run & 1) == 1) continue; // Odd number of backslashes, thus escaped.
						
						switch(c) {
							case '\"': if((flags & FLAG_QUOTED_SINGLE) == 0) flags ^= FLAG_QUOTED_DOUBLE; break;
							case '\'': if((flags & FLAG_QUOTED_DOUBLE) == 0) flags ^= FLAG_QUOTED_SINGLE; break;
						}
						
						break;
					}
					case '}': if((flags & FLAG_QUOTED) == 0) ++countObject; break;
					case ']': if((flags & FLAG_QUOTED) == 0) ++countArray;  break;
					case '{': if((flags & FLAG_QUOTED) == 0 && countObject-- == 0 && countArray  == 0) return i; break;
					case '[': if((flags & FLAG_QUOTED) == 0 && countArray--  == 0 && countObject == 0) return i; break;
				}
			}
			
			return -1;
		}
		
		// Assuming the start position is at an object boundary, i.e. the output of startOfObject.
		private static final int endOfCollection(String string, int start) {
			for(int i = start, countObject = 0, countArray = 0, flags = 0, l = string.length(); i < l; ++i) {
				if((flags & FLAG_ESCAPED) != 0) {
					flags ^= FLAG_ESCAPED;
					continue; // Escaped, skip the character.
				}
				
				switch(string.charAt(i)) {
					case '\\': flags ^= FLAG_ESCAPED; continue;
					case '\"': if((flags & FLAG_QUOTED_SINGLE) == 0) flags ^= FLAG_QUOTED_DOUBLE; break;
					case '\'': if((flags & FLAG_QUOTED_DOUBLE) == 0) flags ^= FLAG_QUOTED_SINGLE; break;
					case '{':  if((flags & FLAG_QUOTED) == 0) ++countObject; break;
					case '[':  if((flags & FLAG_QUOTED) == 0) ++countArray;  break;
					case '}':  if((flags & FLAG_QUOTED) == 0 && --countObject == 0 && countArray  == 0) return i; break;
					case ']':  if((flags & FLAG_QUOTED) == 0 && --countArray  == 0 && countObject == 0) return i; break;
				}
			}
			
			return -1;
		}
		
		// Assuming the start and end positions being inside the same array.
		private static final int countItems(String string, int start, int end) {
			int count = 0;
			
			for(int i = start, countObject = 0, countArray = 0, flags = 0; i < end; ++i) {
				if((flags & FLAG_ESCAPED) != 0) {
					flags ^= FLAG_ESCAPED;
					continue; // Escaped, skip the character.
				}
				
				switch(string.charAt(i)) {
					case '\\': flags ^= FLAG_ESCAPED; continue;
					case '\"': if((flags & FLAG_QUOTED_SINGLE) == 0) flags ^= FLAG_QUOTED_DOUBLE; break;
					case '\'': if((flags & FLAG_QUOTED_DOUBLE) == 0) flags ^= FLAG_QUOTED_SINGLE; break;
					case '{':  if((flags & FLAG_QUOTED) == 0) ++countObject; break;
					case '}':  if((flags & FLAG_QUOTED) == 0) --countObject; break;
					case '[':  if((flags & FLAG_QUOTED) == 0) ++countArray;  break;
					case ']':  if((flags & FLAG_QUOTED) == 0) --countArray; break;
					case ',':  if((flags & FLAG_QUOTED) == 0 && countObject == 0 && countArray == 0) ++count; break;
				}
			}
			
			return count;
		}
		
		// Assuming the start position is inside the object we're trying to find.
		private static final NamedObject outerNamedObject(String string, int start) {
			int end;
			if((start = startOfCollection(string, start)) < 0) return null;
			if((end   =   endOfCollection(string, start)) < 0) return null;
			
			String content = string.substring(start, end + 1);
			List<String> names = new ArrayList<>();
			Matcher matcher = REGEX_OBJECT_NAME.matcher(string);
			
			// We need to extract the object's name. We do this by iteratively finding
			// the parent's object's name. Each found segment is in region defined as
			// <parent_start, object_start> and is of pattern `REGEX_OBJECT_NAME`.
			// We extract the name by using a RegExp fixed from the end, thus the `$`.
			// For an object inside an array its captured name would be null, in that
			// case we count objects before it, which results in its index within that
			// array. All of this will yield the name chain in reversed order.
			while(
				matcher
					.region(start = startOfCollection(string, (end = start) - 1), end + 1)
					.find()
				&& names.add(
					matcher.group(1) == null
						? String.valueOf(countItems(string, start + 1, end))
						: matcher.group(1)
				)
				&& start > 0
			);
			
			Collections.reverse(names);
			String name = String.join(".", names);
			
			return new NamedObject(name, JSON.read(content));
		}
		
		public static final NextJS extract(Document document) {
			Element script = document.selectFirst("script[type='application/json']#__NEXT_DATA__");
			
			if(script == null) {
				throw new IllegalStateException("Document doesn't contain Next data");
			}
			
			return new NextJS(script.html());
		}
		
		public final Stream<NamedObject> objectsOf(String typename) {
			Regex regex = Regex.of(String.format(
				"(?uis)\"__typename\"\\s*:\\s*\"%1$s\"",
				Regex.quote(typename)
			));
			
			return (
				regex.matcher(json).results()
					.map((mr) -> outerNamedObject(json, mr.start() - 1))
					.filter(Objects::nonNull)
			);
		}
		
		public final JSONCollection collectionOf(String typename) {
			return objectsOf(typename).findFirst().map(NamedObject::object).orElse(null);
		}
		
		public static final class NamedObject {
			
			private final String name;
			private final JSONCollection object;
			
			public NamedObject(String name, JSONCollection object) {
				this.name = Objects.requireNonNull(name);
				this.object = Objects.requireNonNull(object);
			}
			
			public String name() { return name; }
			public JSONCollection object() { return object; }
		}
	}
	
	private static interface CT {
		
		void extractJobs(URI sourceUri, Document document, List<ExtractJob> jobs) throws Exception;
		boolean isCompatible(URI uri);
	}
	
	private static enum CT_iVysilani implements CT {
		INSTANCE;
		
		private static final String SUBDOMAIN = "www";
		
		private static final String API_ORIGIN = "ivysilani";
		private static final String API_CLIENT = "iVysilaniWeb";
		
		@Override
		public final void extractJobs(URI sourceUri, Document document, List<ExtractJob> jobs) throws Exception {
			NextJS nextJS = NextJS.extract(document);
			Stream<JSONCollection> objects = nextJS.objectsOf("MediumMeta")
				.filter((v) -> "props.pageProps.data.mediaMeta".equals(v.name()))
				.map(NextJS.NamedObject::object);
			String deviceId = randomUUID();
			String title = MediaTitle.ofMedia(nextJS, "Unknown");
			
			for(JSONCollection object : Utils.iterable(objects.iterator())) {
				String idec = object.getString("idec");
				VOD.ExternalSourceInfo source = new VOD.ExternalSourceInfo(idec, deviceId, API_ORIGIN, API_CLIENT);
				jobs.add(new SourceInfoExtractJob(sourceUri, source, title));
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
	
	private static enum CT_Decko implements CT {
		INSTANCE;
		
		private static final String SUBDOMAIN = "decko";
		
		private static final String FORMAT_SHOW_URL = "https://www.ceskatelevize.cz/porady/%{show_id}s-%{show_code}s/";
		private static final Regex REGEX_VIDEO = Regex.of("^https?://decko.ceskatelevize.cz/video/([^/]+)/?$");
		private static final Regex REGEX_SHOW_CODE = Regex.of("^https?://decko.ceskatelevize.cz/([^/]+)/?$");
		
		private static final String API_ORIGIN = "decko";
		private static final String API_CLIENT = "DeckoWeb";
		
		@Override
		public final void extractJobs(URI sourceUri, Document document, List<ExtractJob> jobs) throws Exception {
			// On a Decko show's page there are all episodes, therefore we have to return media of all episodes
			// that are present there. This is a little bit more complicated since not all episodes are present
			// on the page when the page is loaded and other episodes are loaded dynamically using AJAX.
			// Instead we obtain the show's code and obtain all episodes from Porady show's page. This way
			// we can use the already existing procedures and it also returns complete results.
			String url = document.baseUri();
			
			if(url == null || url.isBlank()) {
				Element elURL = document.selectFirst("meta[property='og:url']");
				
				if(elURL != null) {
					url = elURL.attr("content") + "/";
				}
			}
			
			if(url == null || url.isBlank()) {
				return; // Invalid URL
			}
			
			Matcher matcherUrl;
			
			// Support direct video links as well
			if((matcherUrl = REGEX_VIDEO.matcher(url)).matches()) {
				String deviceId = randomUUID();
				
				// Use the video player query attribute rather than the URL's, just to be safe
				for(Element elPlayer : document.select(".media-player-plain__video")) {
					String query = elPlayer.attr("data-player-query").replace(" ", "").replace("/", "");
					QueryArgument args = Net.queryDestruct(query);
					String idec = args.valueOf("IDEC");
					VOD.ExternalSourceInfo source = new VOD.ExternalSourceInfo(idec, deviceId, API_ORIGIN, API_CLIENT);
					jobs.add(new SourceInfoExtractJob(sourceUri, source, null));
				}
			}
			
			if(!(matcherUrl = REGEX_SHOW_CODE.matcher(url)).matches()) {
				return; // Cannot obtain the show code
			}
			
			String showCode = matcherUrl.group(1);
			String showId = API.getShowId(showCode);
			String showURL = Utils.format(FORMAT_SHOW_URL, "show_id", showId, "show_code", showCode);
			Program program = new Program(Net.uri(showURL), showCode);
			
			// Obtain all the episodes to extract media sources from
			ListTask<Episode> task = ListTask.of((t) -> {
				API.getEpisodes(t, program, WebMediaMetadataExtractor.extract(HTML.from(program.uri()), true));
			});
			
			task.startAndWait();
			
			CT_iVysilani ctInstance = CT_iVysilani.INSTANCE;
			for(Episode episode : task.list()) {
				ctInstance.extractJobs(sourceUri, HTML.from(episode.uri()), jobs);
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
	
	private static enum CT_24 implements CT {
		INSTANCE;
		
		private static final String SUBDOMAIN = "ct24";
		
		private static final String API_ORIGIN = "ct24";
		private static final String API_CLIENT = "CT24Web";
		
		@Override
		public final void extractJobs(URI sourceUri, Document document, List<ExtractJob> jobs) throws Exception {
			NextJS nextJS = NextJS.extract(document);
			Stream<JSONCollection> objects = nextJS.objectsOf("Video_Out")
				.filter((v) -> "props.pageProps.videoModel".equals(v.name()))
				.map(NextJS.NamedObject::object);
			String sessionId = randomUUID();
			
			for(JSONCollection object : Utils.iterable(objects.iterator())) {
				String versionId = object.getString("origin.versionId");
				String title = object.getString("title");
				VOD.InternalSourceInfo source = new VOD.InternalSourceInfo(versionId, sessionId, API_ORIGIN, API_CLIENT);
				jobs.add(new SourceInfoExtractJob(sourceUri, source, title));
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
	
	private static enum CT_Sport implements CT {
		INSTANCE;
		
		private static final String SUBDOMAIN = "sport";
		
		private static final String API_ORIGIN = "sport";
		private static final String API_CLIENT = "SportWeb";
		
		@Override
		public final void extractJobs(URI sourceUri, Document document, List<ExtractJob> jobs) throws Exception {
			NextJS nextJS = NextJS.extract(document);
			Stream<JSONCollection> objects = nextJS.objectsOf("Video_Out").map(NextJS.NamedObject::object);
			String sessionId = randomUUID();
			
			for(JSONCollection object : Utils.iterable(objects.iterator())) {
				String versionId = object.getString("origin.versionId");
				String title = object.getString("title");
				VOD.InternalSourceInfo source = new VOD.InternalSourceInfo(versionId, sessionId, API_ORIGIN, API_CLIENT);
				jobs.add(new SourceInfoExtractJob(sourceUri, source, title));
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
	
	private static enum CT_Art implements CT {
		INSTANCE;
		
		private static final String SUBDOMAIN = "art";
		private static final String SELECTOR_VIDEO = ".popup-video";
		
		private static final String API_ORIGIN = "artzona";
		private static final String API_CLIENT = "ArtWeb";
		
		private static final String fixPlayerUri(String uri) {
			String[] parts = Utils.OfString.split(uri, "?", 2);
			if(parts.length != 2) return uri;
			return parts[0] + '?' + parts[1].replace(" ", "%20").replace("/", "%2F");
		}
		
		@Override
		public final void extractJobs(URI sourceUri, Document document, List<ExtractJob> jobs) throws Exception {
			String deviceId = randomUUID();
			
			// Extract all the videos on the page
			for(Element elVideo : document.select(SELECTOR_VIDEO)) {
				URI uri = Net.uri(Net.uriFix(fixPlayerUri(elVideo.absUrl("href"))));
				QueryArgument query = Net.queryDestruct(uri);
				
				QueryArgument arg;
				if((arg = query.argumentOf("bonus")) != null) {
					VOD.BonusSourceInfo source = new VOD.BonusSourceInfo(arg.value(), deviceId, API_ORIGIN, API_CLIENT);
					jobs.add(new SourceInfoExtractJob(sourceUri, source, null));
				} else if((arg = query.argumentOf("IDEC")) != null) {
					String idec = arg.value().replace(" ", "").replace("/", "");
					VOD.ExternalSourceInfo source = new VOD.ExternalSourceInfo(idec, deviceId, API_ORIGIN, API_CLIENT);
					jobs.add(new SourceInfoExtractJob(sourceUri, source, null));
				}
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
	
	private static enum CT_Edu implements CT {
		INSTANCE;
		
		private static final String SUBDOMAIN = "edu";
		private static final String SELECTOR_PLAYER = ".video-player";
		
		private static final String API_ORIGIN = "edu";
		private static final String API_CLIENT = "EduWeb";
		
		@Override
		public final void extractJobs(URI sourceUri, Document document, List<ExtractJob> jobs) throws Exception {
			String deviceId = randomUUID();
			
			for(Element elPlayer : document.select(SELECTOR_PLAYER)) {
				String idec = elPlayer.attr("data-idec");
				VOD.ExternalSourceInfo source = new VOD.ExternalSourceInfo(idec, deviceId, API_ORIGIN, API_CLIENT);
				jobs.add(new SourceInfoExtractJob(sourceUri, source, null));
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
}