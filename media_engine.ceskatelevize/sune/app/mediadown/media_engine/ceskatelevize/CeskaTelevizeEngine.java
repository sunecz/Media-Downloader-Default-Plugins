package sune.app.mediadown.media_engine.ceskatelevize;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.Shared;
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
import sune.app.mediadown.net.Net;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Reflection;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Web.PostRequest;
import sune.app.mediadown.util.Web.Request;
import sune.app.mediadown.util.Web.StreamResponse;
import sune.app.mediadown.util.Web.StringResponse;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDF;
import sune.util.ssdf2.SSDObject;

public final class CeskaTelevizeEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	private static final CT[] SUPPORTED_WEBS = {
		CT_iVysilani.getInstance(),
		CT_Porady.getInstance(),
		CT_Decko.getInstance(),
		CT_24.getInstance(),
		CT_Sport.getInstance(),
		CT_Art.getInstance(),
		CT_Edu.getInstance(),
	};
	
	// Allow to create an instance when registering the engine
	CeskaTelevizeEngine() {
	}
	
	private static final boolean checkURLSubdomain(URI uri, String required) {
		String[] hostParts = uri.getHost().split("\\.", 2);
		return hostParts.length > 1 && hostParts[0].equalsIgnoreCase(required);
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> {
			Set<API.ProgramWrapper> accumulator = new ConcurrentSkipListSet<>();
			
			(new IntConcurrentLoop() {
				
				@Override
				protected void iteration(Integer category) throws Exception {
					ListTask<API.ProgramWrapper> t = API.getPrograms(category);
					t.forwardAdd(accumulator);
					t.startAndWait();
				}
			}).iterate(API.categories());
			
			for(API.ProgramWrapper wrapper : accumulator) {
				if(!task.add(wrapper.program())) {
					return; // Do not continue
				}
			}
		});
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return ListTask.of((task) -> {
			// We need to get the IDEC of the given program first
			Document document = Utils.document(program.uri());
			WebMediaMetadata metadata = WebMediaMetadataExtractor.extract(document);
			API.getEpisodes(task, program, metadata.IDEC());
		});
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			List<ExtractJob> jobs = new ArrayList<>();
			MediaSource source = MediaSource.of(this);
			CT ct = Arrays.stream(SUPPORTED_WEBS).filter((c) -> c.isCompatible(uri)).findFirst().orElse(null);
			
			if(ct != null) {
				ct.getExtractJobs(Utils.document(uri), jobs);
			}
			
			for(ExtractJob job : jobs) {
				String videoURL = job.url;
				
				switch(job.method) {
					case SOURCE_INFO: {
						SourceInfo info = SourceInfoExtractor.acquire(videoURL);
						
						if(info != null) {
							PlaylistData playlistData = PlaylistDataGetter.get(videoURL, info);
							Map<String, String> headers = Utils.toMap("X-Requested-With", "XMLHttpRequest");
							Request request = new GetRequest(Net.url(playlistData.url), Shared.USER_AGENT, headers);
							
							SSDCollection json;
							try(StreamResponse response = Web.requestStream(request)) {
								json = SSDF.readJSON(response.stream);
							}
							
							SSDCollection playlist = json.getDirectCollection("playlist");
							List<SSDCollection> mediaItems
								= StreamSupport.stream(Spliterators.spliterator(playlist.collectionsIterator(),
																				playlist.length(),
																				Spliterator.ORDERED),
													   false)
											   .filter((item) -> !item.getString("type").equalsIgnoreCase("TRAILER"))
											   .collect(Collectors.toList());
							
							for(SSDCollection mediaItem : mediaItems) {
								String streamURL = mediaItem.getString("streamUrls.main", null);
								
								if(streamURL != null) {
									List<Media.Builder<?, ?>> media = MediaUtils.createMediaBuilders(source,
										Net.uri(streamURL), uri, job.title, MediaLanguage.UNKNOWN,
										MediaMetadata.empty());
									
									// Check, if the media has some additional subtitles
									SSDCollection subtitlesArray;
									if((subtitlesArray = Opt.of(mediaItem.getDirectCollection("subtitles", null))
											.ifTrue(Objects::nonNull)
											.orElseGet(SSDCollection::emptyArray)).length() > 0) {
										// Parse the subtitles and add them to all obtained media
										for(SSDCollection mediaSubtitles : subtitlesArray.collectionsIterable()) {
											MediaLanguage subLanguage = MediaLanguage.ofCode(mediaSubtitles.getDirectString("code"));
											String subURL = mediaSubtitles.getDirectString("url");
											MediaFormat subFormat = MediaFormat.fromPath(subURL);
											SubtitlesMedia.Builder<?, ?> subtitles = SubtitlesMedia.simple().source(source)
													.uri(Net.uri(subURL)).format(subFormat).language(subLanguage);
											media.forEach((m) -> MediaUtils.appendMedia((MediaContainer.Builder<?, ?>) m, subtitles));
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
						break;
					}
					case NONE: {
						if(videoURL != null) {
							List<Media> media = MediaUtils.createMedia(source, Net.uri(videoURL), uri,
								job.title, MediaLanguage.UNKNOWN, MediaMetadata.empty());
							
							for(Media s : media) {
								if(!task.add(s)) {
									return; // Do not continue
								}
							}
						}
						break;
					}
				}
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
		
		private static final Program parseProgram(SSDCollection data) {
			String id = data.getDirectString("id");
			String url = programSlugToURL(data.getDirectString("slug"));
			String title = data.getDirectString("title");
			return new Program(Net.uri(url), title, "id", id);
		}
		
		private static final Episode parseEpisode(Program program, SSDCollection data) {
			String id = data.getDirectString("id");
			String url = episodeSlugToURL(program, id);
			String title = data.getDirectString("title");
			boolean playable = data.getDirectBoolean("playable");
			return new Episode(program, Net.uri(url), title, "id", id, "playable", playable);
		}
		
		private static final String createRequestBody(String operationName, String query, Object... args) {
			if((args.length & 1) != 0)
				throw new IllegalArgumentException("Arguments length must be even.");
			SSDCollection json = SSDCollection.empty();
			json.set("operationName", operationName);
			json.set("query", query);
			SSDCollection vars = SSDCollection.empty();
			for(int i = 0, l = args.length; i < l; i += 2) {
				String name = (String) args[i];
				Object value = args[i + 1];
				if(value != null) { // Do not permit null values
					vars.set(name, SSDObject.of(name, value));
				}
			}
			json.set("variables", vars);
			return json.toJSON(true).replaceAll("\\n", "\\\\n");
		}
		
		private static final SSDCollection doOperation(String operationName, String query, Object... variables) throws Exception {
			String body = createRequestBody(operationName, query, variables);
			Map<String, String> headers = Map.of("Content-Type", "application/json", "Referer", REFERER);
			StringResponse response = Web.request(new PostRequest(Net.url(URL), Shared.USER_AGENT, null, headers).toBodyRequest(body));
			if(response.code != 200)
				throw new IllegalStateException("API returned non-OK code: " + response.code + ". Body: " + response.content);
			return JSON.read(response.content);
		}
		
		public static final CollectionAPIResult getProgramsWithCategory(int categoryId, int offset, int length) throws Exception {
			SSDCollection json = doOperation("GetCategoryById", QUERY_GET_PROGRAMS_BY_CATEGORY,
				"categoryId", String.valueOf(categoryId),
				"limit", length,
				"offset", offset,
				"order", "asc",
				"orderBy", "alphabet");
			SSDCollection items = json.getCollection("data.showFindByGenre.items");
			int total = json.getInt("data.showFindByGenre.totalCount");
			return new CollectionAPIResult(items, total);
		}
		
		public static final CollectionAPIResult getEpisodes(String idec, int offset, int length) throws Exception {
			return getEpisodes(idec, offset, length, null);
		}
		
		public static final CollectionAPIResult getEpisodes(String idec, int offset, int length, String seasonId) throws Exception {
			SSDCollection json = doOperation("GetEpisodes", QUERY_GET_EPISODES,
				"idec", idec,
				"limit", length,
				"offset", offset,
				"orderBy", "newest",
				"seasonId", seasonId);
			SSDCollection items = json.getCollection("data.episodesPreviewFind.items");
			int total = json.getInt("data.episodesPreviewFind.totalCount");
			return new CollectionAPIResult(items, total);
		}
		
		public static final ListTask<ProgramWrapper> getPrograms(int categoryId) throws Exception {
			return ListTask.of((task) -> {
				int offset = 0, total = -1;
				CollectionAPIResult result;
				
				loop:
				do {
					result = getProgramsWithCategory(categoryId, offset, MAX_ITEMS_PER_PAGE);
					
					for(SSDCollection item : result.items().collectionsIterable()) {
						Program program = parseProgram(item);
						
						if(!task.add(new ProgramWrapper(program))) {
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
		
		public static final void getEpisodes(ListTask<Episode> task, Program program, String idec) throws Exception {
			int offset = 0, total = -1;
			CollectionAPIResult result;
			
			loop:
			do {
				result = getEpisodes(idec, offset, MAX_ITEMS_PER_PAGE);
				
				for(SSDCollection item : result.items().collectionsIterable()) {
					Episode episode = parseEpisode(program, item);
					
					if(!task.add(episode)) {
						break loop;
					}
				}
				
				if(total < 0) {
					total = result.total();
				}
				
				offset += MAX_ITEMS_PER_PAGE;
			} while(offset < total);
		}
		
		public static final CollectionAPIResult searchShows(String text, int offset, int limit) throws Exception {
			SSDCollection json = doOperation(
				"SearchShows", QUERY_SEARCH_SHOWS,
				"limit", limit,
				"offset", offset,
				"search", text,
				"onlyPlayable", false
			);
			SSDCollection items = json.getCollection("data.searchShows.items");
			int total = json.getInt("data.searchShows.totalCount");
			return new CollectionAPIResult(items, total);
		}
		
		public static final String getShowId(String showCode) throws Exception {
			return Opt.of(Utils.stream(searchShows(showCode, 0, 1).items().collectionsIterable())
			                   .filter((c) -> c.getDirectString("code").equals(showCode))
			                   .findFirst())
					  .<Optional<SSDCollection>>castAny()
					  .ifTrue(Optional::isPresent)
					  .map((o) -> o.get().getDirectString("id"))
					  .orElse(null);
		}
		
		public static final int[] categories() {
			return CATEGORIES;
		}
		
		static final class CollectionAPIResult {
			
			private final SSDCollection items;
			private final int total;
			
			public CollectionAPIResult(SSDCollection items, int total) {
				this.items = items;
				this.total = total;
			}
			
			public SSDCollection items() {
				return items;
			}
			
			public int total() {
				return total;
			}
		}
		
		static final class ProgramWrapper implements Comparable<ProgramWrapper> {
			
			private final Program program;
			private SoftReference<BigInteger> id;
			
			public ProgramWrapper(Program program) {
				this.program = Objects.requireNonNull(program);
			}
			
			private final BigInteger id() {
				return (id == null ? id = new SoftReference<>(new BigInteger(program.<String>get("id"))) : id).get();
			}
			
			@Override
			public boolean equals(Object obj) {
				if(obj == this) return true;
				if(!(obj instanceof ProgramWrapper)) return false;
				ProgramWrapper w = (ProgramWrapper) obj;
				return id().equals(w.id());
			}
			
			@Override
			public int hashCode() {
				return id().hashCode();
			}
			
			@Override
			public int compareTo(ProgramWrapper w) {
				if(Objects.requireNonNull(w) == this) return 0;
				int cmpId = id().compareTo(w.id());
				if(cmpId == 0) return 0;
				int cmpTitle = program.title().compareTo(w.program.title());
				if(cmpTitle == 0) return cmpId;
				return cmpTitle;
			}
			
			public Program program() {
				return program;
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
	
	private static enum ExtractMethod {
		SOURCE_INFO, NONE;
	}
	
	private static final class ExtractJob {
		
		public final String url;
		public final ExtractMethod method;
		public final String title;
		
		public ExtractJob(String url, ExtractMethod method, String title) {
			this.url = url;
			this.method = method;
			this.title = title;
		}
	}
	
	private static final class SourceInfo {
		
		public String type;
		public String id;
		public String baseURL;
		public String wwwServerGet;
		public String requestSource;
		
		public boolean isValid() {
			return Arrays.stream(Utils.array(type, id, baseURL, wwwServerGet, requestSource))
						 .allMatch(Objects::nonNull);
		}
	}
	
	private static final class SourceInfoExtractor {
		
		private static final Regex REGEX_VAR_REQUEST_SOURCE;
		private static final Regex REGEX_VAR_BASE_URL;
		private static final Regex REGEX_VAR_WWW_SERVER_GET;
		private static final Regex REGEX_CALL_GET_PLAYLIST_URL;
		
		static {
			REGEX_VAR_REQUEST_SOURCE = Regex.of("var\\s+requestSource\\s*=\\s*[\"']([^\"']+)[\"']\\s*[^;]+;");
			REGEX_VAR_BASE_URL = Regex.of("var\\s+baseUrl\\s*=\\s*[\"']([^\"']+)[\"'];");
			REGEX_VAR_WWW_SERVER_GET = Regex.of("var\\s+wwwServerGet\\s*=\\s*[\"']([^\"']*)[\"'];");
			REGEX_CALL_GET_PLAYLIST_URL = Regex.of("getPlaylistUrl\\(\\s*(\\[[^;]+\\]),[^;]+\\);");
		}
		
		public static final SourceInfo acquire(String url) {
			Document document = Utils.document(url);
			SourceInfo info = new SourceInfo();
			for(Element script : document.select("script:not([src])")) {
				String content = script.html();
				Matcher matcher;
				if((matcher = REGEX_VAR_REQUEST_SOURCE.matcher(content)).find()) {
					info.requestSource = matcher.group(1);
					// Both variables are in the same script tag
					if((matcher = REGEX_VAR_WWW_SERVER_GET.matcher(content)).find()) {
						info.wwwServerGet = matcher.group(1);
					}
				} else if((matcher = REGEX_VAR_BASE_URL.matcher(content)).find()) {
					info.baseURL = matcher.group(1);
				} else if((matcher = REGEX_CALL_GET_PLAYLIST_URL.matcher(content)).find()) {
					SSDCollection data = JavaScript.readObject(matcher.group(1)).getCollection(0, SSDCollection.empty());
					info.type = data.getDirectString("type", null);
					info.id = data.getDirectString("id", null);
				}
			}
			return info.isValid() ? info : null;
		}
		
		// Forbid anyone to create an instance of this class
		private SourceInfoExtractor() {
		}
	}
	
	private static final class PlaylistData {
		
		public String streamingProtocol;
		public String url;
		
		public boolean isValid() {
			return Arrays.stream(Utils.array(streamingProtocol, url))
						 .allMatch(Objects::nonNull);
		}
	}
	
	private static final class PlaylistDataGetter {
		
		private static final String BASE_URL = "https://www.ceskatelevize.cz";
		
		public static PlaylistData get(String url, SourceInfo info) throws Exception {
			String requestURLRelative = Net.url(url).getPath();
			String endpointURL = BASE_URL + info.baseURL + "/ajax/get-client-playlist/" + info.wwwServerGet;
			Map<String, String> params = Utils.toMap(
				"playlist[0][type]", info.type,
				"playlist[0][id]", info.id,
				"requestUrl", requestURLRelative,
				"requestSource", info.requestSource,
				"type", "html",
				"canPlayDRM", "false"
			);
			Map<String, String> headers = Utils.toMap(
				"X-Requested-With", "XMLHttpRequest",
				"x-addr", "127.0.0.1"
			);
			Request request = new PostRequest(Net.url(endpointURL), Shared.USER_AGENT, params, headers);
			
			SSDCollection json;
			try(StreamResponse response = Web.requestStream(request)) {
				if(response.code != 200) {
					return null;
				}
				
				json = SSDF.readJSON(response.stream);
			}
			
			PlaylistData data = new PlaylistData();
			data.streamingProtocol = json.getDirectString("streamingProtocol", null);
			data.url = json.getDirectString("url", null);
			return data.isValid() ? data : null;
		}
		
		// Forbid anyone to create an instance of this class
		private PlaylistDataGetter() {
		}
	}
	
	private static final class WebMediaMetadata {
		
		private final String idec;
		private final String indexId;
		
		public WebMediaMetadata(String idec, String indexId) {
			this.idec = idec;
			this.indexId = indexId;
		}
		
		public String IDEC() {
			return idec;
		}
		
		public String indexId() {
			return indexId;
		}
	}
	
	private static final class WebMediaMetadataExtractor {
		
		private static final String SELECTOR_SCRIPT = "script#__NEXT_DATA__";
		private static final Regex PATTERN_IDEC = Regex.of("\"idec\":\"([^\"]+)\"");
		private static final Regex PATTERN_INDEX_ID = Regex.of("\"indexId\":\"([^\"]+)\"");
		
		public static final WebMediaMetadata extract(Document document) {
			Element elScript = document.selectFirst(SELECTOR_SCRIPT);
			if(elScript == null) return null; // No metadata script content available
			Matcher matcher = PATTERN_IDEC.matcher(elScript.html());
			if(!matcher.find()) return null; // Content does not contain the needed metadata
			String idec = matcher.group(1);
			String indexId = null;
			// Also check for the index ID, if present
			if((matcher = PATTERN_INDEX_ID.matcher(elScript.html())).find()) {
				indexId = matcher.group(1);
			}
			return new WebMediaMetadata(idec, indexId);
		}
		
		// Forbid anyone to create an instance of this class
		private WebMediaMetadataExtractor() {
		}
	}
	
	private static final class IFrameHelper {
		
		private static final String URL_IFRAME;
		private static final String URL_PLAYER_HASH;
		
		static {
			URL_IFRAME = "https://www.ceskatelevize.cz/ivysilani/embed/iFramePlayer.php?hash=%{hash}s&IDEC=%{idec}s%{index}s";
			URL_PLAYER_HASH = "https://www.ceskatelevize.cz/v-api/iframe-hash/";
		}
		
		public static final String obtainHash() throws Exception {
			StringResponse response = Web.request(new GetRequest(Net.url(URL_PLAYER_HASH), Shared.USER_AGENT));
			return response != null ? response.content : null;
		}
		
		public static final String getURL(String idec, String indexId) throws Exception {
			String strIndex = indexId != null ? "&index=" + indexId : "";
			return Utils.format(URL_IFRAME, "hash", obtainHash(), "idec", idec, "index", strIndex);
		}
		
		// Forbid anyone to create an instance of this class
		private IFrameHelper() {
		}
	}
	
	private static final class LinkingData {
		
		private static LinkingData EMPTY;
		
		private final Document document;
		private final SSDCollection data;
		private final String type;
		
		private LinkingData() {
			this.document = null;
			this.data = null;
			this.type = "none";
		}
		
		private LinkingData(Document document, SSDCollection data) {
			this.document = Objects.requireNonNull(document);
			this.data = Objects.requireNonNull(data);
			this.type = data.getDirectString("@type");
		}
		
		public static final LinkingData empty() {
			return EMPTY == null ? (EMPTY = new LinkingData()) : EMPTY;
		}
		
		public static final List<LinkingData> from(Document document) {
			return document.select("script[type='application/ld+json']")
						.stream()
						.map((s) -> new LinkingData(document, JSON.read(s.html())))
						.collect(Collectors.toList());
		}
		
		public final boolean isEmpty() {
			return data == null;
		}
		
		public final Document document() {
			return document;
		}
		
		public final SSDCollection data() {
			return data;
		}
		
		public final String type() {
			return type;
		}
	}
	
	private static final class LinkingDataTitle {
		
		private static final Regex REGEX_SEASON = Regex.of("^(\\d+|[IVXLCDM]+)(\\.\\s+.*)?$");
		private static final Regex REGEX_EPISODE = Regex.of("^(?:Epizoda\\s+)?([\\d\\s\\+]+)/\\d+(?:\\s+(.*))?$");
		
		public static final String ofTVEpisode(LinkingData ld, String defaultValue) throws Exception {
			if(ld.isEmpty()) return defaultValue;
			
			Document document = ld.document();
			SSDCollection data = ld.data();
			
			String programName = data.getString("partOfTVSeries.name", "");
			String episodeName = data.getDirectString("name", "");
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
			
			Element elData = document.selectFirst("script#__NEXT_DATA__");
			if(elData != null) {
				SSDCollection mediaData = JavaScript.readObject(elData.html());
				SSDCollection meta = mediaData.getCollection("props.pageProps.data.mediaMeta");
				SSDCollection seasons = meta.getCollection("show.seasons", null);
				// Try to obtain the season number
				if(seasons != null) {
					String activeSeasonId = meta.getDirectString("activeSeasonId", null);
					if(activeSeasonId == null || activeSeasonId.equals("null")) {
						// Some episodes can be visible only on the All episodes page. No season
						// can therefore be found so just use some invalid value.
						numSeason = "";
					} else {
						String textSeason = Utils.stream(seasons.collectionsIterable())
								.filter((c) -> c.getDirectString("id", "").equals(activeSeasonId))
								.map((c) -> c.getDirectString("title", ""))
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
						String seasonId = activeSeasonId.equals("null") ? null : activeSeasonId;
						String episodeId = mediaData.getString("props.pageProps.data.mediaMeta.id");
						String showIDEC = mediaData.getString("props.pageProps.data.mediaMeta.show.idec");
						int num = Episodes.indexOf(episodeId, showIDEC, seasonId);
						if(num != -1) numEpisode = String.format("%02d", num + 1);
					}
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
		
		public static final String ofArticle(LinkingData ld, String defaultValue) throws Exception {
			return !ld.isEmpty() ? ld.data().getDirectString("headline", defaultValue) : defaultValue;
		}
	}
	
	private static final class Episodes {
		
		private static Map<String, List<String>> cache;
		
		private static final String programSeasonKey(String programIDEC, String seasonId) {
			return programIDEC + ':' + seasonId;
		}
		
		public static final int indexOf(String episodeId, String programIDEC, String seasonId) throws Exception {
			int offset = 0, total = -1, ctr = 0, index = -1;
			
			// Only create the cache if needed
			if(cache == null)
				cache = new HashMap<>();
			
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
				result = API.getEpisodes(programIDEC, offset, API.MAX_ITEMS_PER_PAGE, seasonId);
				for(SSDCollection item : result.items().collectionsIterable()) {
					String id = item.getDirectString("id", "");
					episodes.add(id); // Put to the cache
					if(id.equals(episodeId)) index = ctr; // Do not break from the loop due to caching
					++ctr;
				}
				if(total < 0)
					total = result.total();
				offset += API.MAX_ITEMS_PER_PAGE;
			} while(index < 0 && offset < total);
			
			return index;
		}
	}
	
	private static interface CT {
		
		void getExtractJobs(Document document, List<ExtractJob> jobs) throws Exception;
		boolean isCompatible(URI uri);
	}
	
	// Context-dependant Singleton instantiator
	private static final class _Singleton {
		
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
	
	private static final class CT_iVysilani implements CT {
		
		private static final String SUBDOMAIN = "www";
		private static final String PATH_PREFIX = "/ivysilani/";
		
		private CT_iVysilani() {}
		public static final CT_iVysilani getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public final void getExtractJobs(Document document, List<ExtractJob> jobs) throws Exception {
			// Try to obtain the media title from its linking data and document
			LinkingData ld = LinkingData.from(document).stream()
					.filter((d) -> d.type().equals("TVEpisode"))
					.findFirst().orElseGet(LinkingData::empty);
			String title = LinkingDataTitle.ofTVEpisode(ld, document.title());
			jobs.add(new ExtractJob(document.baseUri(), ExtractMethod.SOURCE_INFO, title));
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN) && uri.getPath().startsWith(PATH_PREFIX);
		}
	}
	
	private static final class CT_Porady implements CT {
		
		private static final String SUBDOMAIN = "www";
		private static final String PATH_PREFIX = "/porady/";
		
		private CT_Porady() {}
		public static final CT_Porady getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public final void getExtractJobs(Document document, List<ExtractJob> jobs) throws Exception {
			WebMediaMetadata metadata = WebMediaMetadataExtractor.extract(document);
			if(metadata == null) return; // Unable to obtain the ID
			String url = IFrameHelper.getURL(metadata.IDEC(), metadata.indexId());
			// Try to obtain the media title from its linking data and document
			LinkingData ld = LinkingData.from(document).stream()
					.filter((d) -> d.type().equals("TVEpisode"))
					.findFirst().orElseGet(LinkingData::empty);
			String title = LinkingDataTitle.ofTVEpisode(ld, document.title());
			jobs.add(new ExtractJob(url, ExtractMethod.SOURCE_INFO, title));
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN) && uri.getPath().startsWith(PATH_PREFIX);
		}
	}
	
	private static final class CT_Decko implements CT {
		
		private static final String SUBDOMAIN = "decko";
		private static final String FORMAT_SHOW_URL = "https://www.ceskatelevize.cz/porady/%{show_id}s-%{show_code}s/";
		private static final Regex REGEX_SHOW_CODE = Regex.of("^https?://decko.ceskatelevize.cz/([^/]+)/?$");
		
		private CT_Decko() {}
		public static final CT_Decko getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public final void getExtractJobs(Document document, List<ExtractJob> jobs) throws Exception {
			// On a Decko show's page there are all episodes, therefore we have to return media of all episodes
			// that are present there. This is a little bit more complicated since not all episodes are present
			// on the page when the page is loaded and other episodes are loaded dynamically using AJAX.
			// Instead we obtain the show's code and obtain all episodes from Porady show's page. This way
			// we can use the already existing procedures and it also returns complete results.
			
			String url = document.baseUri();
			if(url == null || url.isBlank()) {
				Element elURL = document.selectFirst("meta[property='og:url']");
				if(elURL != null) url = elURL.attr("content") + "/";
			}
			
			if(url == null || url.isBlank()) return; // Invalid URL
			Matcher matcherURL = REGEX_SHOW_CODE.matcher(url);
			if(!matcherURL.matches()) return; // Cannot obtain the show code
			
			String showCode = matcherURL.group(1);
			String showId = API.getShowId(showCode);
			String showURL = Utils.format(FORMAT_SHOW_URL, "show_id", showId, "show_code", showCode);
			Program program = new Program(Net.uri(showURL), showCode);
			
			// Obtain all the episodes to extract media sources from
			ListTask<Episode> task = ListTask.of((t) -> {
				API.getEpisodes(t, program, WebMediaMetadataExtractor.extract(Utils.document(program.uri())).IDEC());
			});
			
			task.startAndWait();
			
			CT_Porady ctInstance = CT_Porady.getInstance();
			for(Episode episode : task.list()) {
				ctInstance.getExtractJobs(Utils.document(episode.uri()), jobs);
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
	
	private static final class CT_24 implements CT {
		
		private static final String SUBDOMAIN = "ct24";
		private static final String SELECTOR_VIDEO = ".video-player > .media-ivysilani-placeholder";
		private static final String URL_PLAYER_IFRAME;
		private static final Regex REGEX_PLAYER_HASH;
		
		static {
			URL_PLAYER_IFRAME = "%{url}s&hash=%{hash}s";
			REGEX_PLAYER_HASH = Regex.of("media_ivysilani:\\{hash:\"(.*?)\"\\}");
		}
		
		private CT_24() {}
		public static final CT_24 getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public final void getExtractJobs(Document document, List<ExtractJob> jobs) throws Exception {
			String playerHash = null;
			for(Element elScript : document.select("script")) {
				String content = elScript.html();
				Matcher matcher = REGEX_PLAYER_HASH.matcher(content);
				if(matcher.find()) {
					playerHash = matcher.group(1);
					break;
				}
			}
			if(playerHash != null) {
				// Try to obtain the media title from its linking data and document
				LinkingData ld = LinkingData.from(document).stream()
						.filter((d) -> d.type().equals("Article"))
						.findFirst().orElseGet(LinkingData::empty);
				String title = LinkingDataTitle.ofArticle(ld, document.title());
				// Add all the videos on the page
				for(Element elVideo : document.select(SELECTOR_VIDEO)) {
					String url = Utils.format(URL_PLAYER_IFRAME, "url", elVideo.attr("data-url"), "hash", playerHash);
					jobs.add(new ExtractJob(url, ExtractMethod.SOURCE_INFO, title));
				}
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
	
	private static final class CT_Sport implements CT {
		
		private static final String SUBDOMAIN = "sport";
		private static final String API_URL = "https://playlist.ceskatelevize.cz/";
		private static final String SELECTOR_VIDEO = ""
				+ "#article [data-ctcomp-data][data-ctcomp='Video'],"
				+ "#article [data-ctcomp-data][data-ctcomp='VideoGallery']";
		
		private CT_Sport() {}
		public static final CT_Sport getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public final void getExtractJobs(Document document, List<ExtractJob> jobs) throws Exception {
			Set<String> allowedNames = Set.of("id", "key", "date", "requestSource", "quality", "region", "title");
			List<SSDCollection> items = new ArrayList<>();
			Set<String> ids = new HashSet<>();
			
			for(Element elVideo : document.select(SELECTOR_VIDEO)) {
				SSDCollection data = JSON.read(elVideo.attr("data-ctcomp-data"));
				
				List<SSDCollection> children = new ArrayList<>();
				if(data.hasDirectCollection("items")) { // VideoGallery
					for(SSDCollection item : data.getDirectCollection("items").collectionsIterable()) {
						children.add(item.getCollection("video.data"));
					}
				} else { // Video
					children.add(data);
				}
				
				for(SSDCollection child : children) {
					SSDCollection playlist = child.getCollection("source.playlist");
					// Collect individual playlist item's information
					for(SSDCollection item : playlist.collectionsIterable()) {
						String id = item.getDirectString("id", "");
						if(ids.contains(id)) continue;
						ids.add(id);
						SSDCollection newItem = SSDCollection.empty();
						String type = item.getDirectString("type", "vod").toLowerCase();
						newItem.setDirect("type", type);
						newItem.setDirect("playerType", "dash");
						newItem.setDirect("drm", 0);
						newItem.setDirect("canBePlay", 1);
						StreamSupport.stream(item.objectsIterable().spliterator(), false)
									 .filter((o) -> allowedNames.contains(o.getName()))
									 .forEach((o) -> newItem.setDirect(o.getName(), o));
						items.add(newItem);
					}
				}
			}
			
			URL apiURL = Net.url(API_URL);
			for(SSDCollection item : items) {
				// Construct the HTTP POST body as a JSON object
				SSDCollection coll = SSDCollection.empty();
				SSDCollection array = SSDCollection.emptyArray();
				String type = item.getDirectString("type");
				String title = item.getDirectString("title");
				item.removeDirect("type");
				item.removeDirect("title");
				array.add(item);
				coll.setDirect("contentType", type);
				coll.setDirect("items", array);
				Request request = new PostRequest(apiURL, Shared.USER_AGENT, Map.of("data", coll.toJSON(true)));
				// Do the request to obtain the stream URLs
				try(StreamResponse response = Web.requestStream(request)) {
					SSDCollection result = JSON.read(response.stream);
					for(SSDCollection playlistItem : result.getCollection("RESULT.playlist").collectionsIterable()) {
						String streamURL = playlistItem.getString("streamUrls.main");
						jobs.add(new ExtractJob(streamURL, ExtractMethod.NONE, title));
					}
				}
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
	
	private static final class CT_Art implements CT {
		
		private static final String SUBDOMAIN = "art";
		private static final String SELECTOR_VIDEO = ".popup-video";
		
		private CT_Art() {}
		public static final CT_Art getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public final void getExtractJobs(Document document, List<ExtractJob> jobs) throws Exception {
			// Try to obtain the document's title from meta tags first, then from the title tag
			String title = Opt.of(document.selectFirst("meta[property='og:title']"))
					.ifTrue(Objects::nonNull)
					.map((e) -> e.attr("content"))
					.ifTrue((t) -> t != null && !t.isBlank())
					.orElseGet(document::title);
			// Extract all the videos on the page
			for(Element elVideo : document.select(SELECTOR_VIDEO)) {
				String url = Net.uriFix(elVideo.attr("href"));
				jobs.add(new ExtractJob(url, ExtractMethod.SOURCE_INFO, title));
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
	
	private static final class CT_Edu implements CT {
		
		private static final String SUBDOMAIN = "edu";
		private static final String SELECTOR_VIDEO = ".video-player";
		private static final String URL = "https://edu.ceskatelevize.cz";
		
		private CT_Edu() {}
		public static final CT_Edu getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public final void getExtractJobs(Document document, List<ExtractJob> jobs) throws Exception {
			// Try to obtain the document's title from linking data first, then from the title tag
			String title = Opt.of(LinkingData.from(document).stream()
			                        .filter((l) -> l.type().equals("VideoObject"))
			                        .findFirst().orElse(null))
			                  .ifTrue(Objects::nonNull)
			                  .map((l) -> l.data().getDirectString("name"))
			                  .orElseGet(document::title);
			// Extract all the videos on the page
			for(Element elVideo : document.select(SELECTOR_VIDEO)) {
				Map<String, String> data = elVideo.attributes().dataset();
				String endpoint = data.remove("endpoint");
				String urlData = data.entrySet().stream()
					.filter((e) -> !e.getValue().isEmpty())
					.map((e) -> JavaScript.encodeURIComponent(e.getKey().equals("idec")
					                                              ? e.getKey().toUpperCase()
					                                              : e.getKey().toLowerCase()) + '='
					          + JavaScript.encodeURIComponent(e.getValue()))
					.reduce("", (a, b) -> a + '&' + b);
				String url = URL + endpoint + '?' + urlData.substring(1);
				jobs.add(new ExtractJob(url, ExtractMethod.SOURCE_INFO, title));
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
}