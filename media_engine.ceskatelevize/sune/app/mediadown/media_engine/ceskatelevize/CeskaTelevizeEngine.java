package sune.app.mediadown.media_engine.ceskatelevize;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.concurrent.VarLoader;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.gui.Dialog;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.Media;
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
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Reflection;
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
	
	private static final Translation translation() {
		String path = "plugin." + PLUGIN.getContext().getPlugin().instance().name();
		return MediaDownloader.translation().getTranslation(path);
	}
	
	private static final boolean checkURLSubdomain(URI uri, String required) {
		String[] hostParts = uri.getHost().split("\\.", 2);
		return hostParts.length > 1 && hostParts[0].equalsIgnoreCase(required);
	}
	
	private static final void displayError(String name) {
		Translation tr = translation().getTranslation("error");
		String message = tr.getSingle("value." + name);
		tr = tr.getTranslation("media_error");
		Dialog.showContentInfo(tr.getSingle("title"), tr.getSingle("text"), message);
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
			Document document = HTML.from(program.uri());
			WebMediaMetadata metadata = WebMediaMetadataExtractor.extract(document);
			API.getEpisodes(task, program, metadata.idec());
		});
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			CT ct = Arrays.stream(SUPPORTED_WEBS).filter((c) -> c.isCompatible(uri)).findFirst().orElse(null);
			
			if(ct == null) {
				return; // Not supported
			}
			
			List<ExtractJob> jobs = new ArrayList<>();
			MediaSource source = MediaSource.of(this);
			ct.getExtractJobs(HTML.from(uri), jobs);
			
			for(ExtractJob job : jobs) {
				URI videoUri = job.uri();
				
				switch(job.method()) {
					case SOURCE_INFO: {
						VOD.Playlist playlist = null;
						SourceInfo info = job.source();
						
						// Always try the new API first, the old one just as a fallback
						for(VOD.API api : List.of(VOD.v1(), VOD.v0())) {
							playlist = api.ofExternal(info);
							
							if(playlist != null) {
								break; // Successfully obtained
							}
						}
						
						if(playlist == null) {
							continue; // Unsuccessful, try another job, if any
						}
						
						for(VOD.Playlist.Stream stream : playlist.streams()) {
							URI finalUri;
							
							try(Response.OfStream response = Web.peek(Request.of(stream.uri()).HEAD())) {
								finalUri = response.uri();
							}
							
							List<Media.Builder<?, ?>> media = MediaUtils.createMediaBuilders(
								source, finalUri, uri, job.title(), MediaLanguage.UNKNOWN, MediaMetadata.empty()
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
									
									media.forEach((m) -> MediaUtils.appendMedia(Utils.cast(m), subtitles));
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
						
						break;
					}
					case NONE: {
						List<Media> media = MediaUtils.createMedia(
							source, videoUri, uri, job.title(), MediaLanguage.UNKNOWN, MediaMetadata.empty()
						);
						
						for(Media s : media) {
							if(!task.add(s)) {
								return; // Do not continue
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
		
		private static final Program parseProgram(JSONCollection data) {
			String id = data.getString("id");
			String url = programSlugToURL(data.getString("slug"));
			String title = data.getString("title");
			return new Program(Net.uri(url), title, "id", id);
		}
		
		private static final Episode parseEpisode(Program program, JSONCollection data) {
			String id = data.getString("id");
			String url = episodeSlugToURL(program, id);
			String title = data.getString("title");
			boolean playable = data.getBoolean("playable");
			return new Episode(program, Net.uri(url), title, "id", id, "playable", playable);
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
		
		private static final JSONCollection doOperation(String operationName, String query, Object... variables) throws Exception {
			String body = createRequestBody(operationName, query, variables);
			String contentType = "application/json";
			HttpHeaders headers = Web.Headers.ofSingle("Referer", REFERER);
			try(Response.OfStream response = Web.requestStream(Request.of(Net.uri(URL)).headers(headers).POST(body, contentType))) {
				if(response.statusCode() != 200) {
					throw new IllegalStateException(
						"API returned non-OK code: " + response.statusCode() + ".\n" +
						"Body: " + Utils.streamToString(response.stream())
					);
				}
				
				return JSON.read(response.stream());
			}
		}
		
		public static final CollectionAPIResult getProgramsWithCategory(int categoryId, int offset, int length) throws Exception {
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
		
		public static final CollectionAPIResult getEpisodes(String idec, int offset, int length) throws Exception {
			return getEpisodes(idec, offset, length, null);
		}
		
		public static final CollectionAPIResult getEpisodes(String idec, int offset, int length, String seasonId) throws Exception {
			JSONCollection json = doOperation("GetEpisodes", QUERY_GET_EPISODES,
				"idec", idec,
				"limit", length,
				"offset", offset,
				"orderBy", "newest",
				"seasonId", seasonId);
			JSONCollection items = json.getCollection("data.episodesPreviewFind.items");
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
					
					for(JSONCollection item : result.items().collectionsIterable()) {
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
				
				for(JSONCollection item : result.items().collectionsIterable()) {
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
			return Opt.of(Utils.stream(searchShows(showCode, 0, 1).items().collectionsIterable())
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
		
		private final URI uri;
		private final ExtractMethod method;
		private final SourceInfo source;
		private final String title;
		
		public ExtractJob(URI uri, ExtractMethod method, SourceInfo source, String title) {
			this.uri = Objects.requireNonNull(uri);
			this.method = Objects.requireNonNull(method);
			this.source = Objects.requireNonNull(source);
			this.title = Objects.requireNonNull(title);
		}
		
		public URI uri() { return uri; }
		public ExtractMethod method() { return method; }
		public SourceInfo source() { return source; }
		public String title() { return title; }
	}
	
	private static final class WebMediaMetadata {
		
		private final String idec;
		
		public WebMediaMetadata(String idec) {
			this.idec = idec;
		}
		
		public String idec() {
			return idec;
		}
	}
	
	private static final class WebMediaMetadataExtractor {
		
		private static final String SELECTOR_SCRIPT = "script#__NEXT_DATA__";
		private static final Regex PATTERN_IDEC = Regex.of("\"idec\":\"(?<idec>[^\"]+)\"");
		
		public static final WebMediaMetadata extract(Document document) {
			Element elScript = document.selectFirst(SELECTOR_SCRIPT);
			
			if(elScript == null) {
				return null; // No metadata script content available
			}
			
			Matcher matcher = PATTERN_IDEC.matcher(elScript.html());
			
			if(!matcher.find()) {
				return null; // Content does not contain the needed metadata
			}
			
			return new WebMediaMetadata(matcher.group("idec"));
		}
		
		// Forbid anyone to create an instance of this class
		private WebMediaMetadataExtractor() {
		}
	}
	
	private static final class SourceInfo {
		
		private final String type;
		private final String idec;
		
		private SourceInfo(String type, String idec) {
			this.type = Objects.requireNonNull(type);
			this.idec = Objects.requireNonNull(idec);
		}
		
		public static final SourceInfo ofEpisode(String idec) {
			return new SourceInfo("episode", idec);
		}
		
		public static final SourceInfo ofBonus(String idec) {
			return new SourceInfo("bonus", idec);
		}
		
		public String type() { return type; }
		public String idec() { return idec; }
	}
	
	// Note: Based on https://player.ceskatelevize.cz/_next/static/chunks/695-cede098ec19ef364.js
	private static final class VOD {
		
		private VOD() {}
		
		public static final API v0() { return V0.instance(); }
		public static final API v1() { return V1.instance(); }
		
		// The old API that uses the iFramePlayer.php
		private static final class V0 implements API {
			
			private static final VarLoader<API> instance = VarLoader.of(V0::new);
			
			private static final URI ENDPOINT;
			private static final String REQUEST_URI;
			private static final String REQUEST_SOURCE;
			private static final String STREAM_PROTOCOL;
			
			static {
				ENDPOINT = Net.uri("https://www.ceskatelevize.cz/ivysilani/ajax/get-client-playlist/");
				REQUEST_URI = "/ivysilani/embed/iFramePlayer.php";
				REQUEST_SOURCE = "iVysilani";
				STREAM_PROTOCOL = "dash"; // Either dash or hls
			}
			
			private V0() {}
			
			private static final Playlist.Stream parseStream(JSONCollection collection) {
				URI uri = Net.uri(collection.getString("streamUrls.main"));
				
				if(!collection.hasCollection("subtitles")) {
					return new Playlist.Stream(uri, Map.of());
				}
				
				Map<MediaLanguage, List<URI>> subtitles = new LinkedHashMap<>();
				
				for(JSONCollection item : collection.getCollection("subtitles").collectionsIterable()) {
					MediaLanguage language = MediaLanguage.ofCode(item.getString("code"));
					List<URI> uris = List.of(Net.uri(item.getString("url")));
					subtitles.put(language, uris);
				}
				
				return new Playlist.Stream(uri, subtitles);
			}
			
			private static final Playlist parsePlaylist(URI uri) throws Exception {
				List<Playlist.Stream> streams = new ArrayList<>();
				
				try(Response.OfStream response = Web.requestStream(Request.of(uri).GET())) {
					JSONCollection json = JSON.read(response.stream());
					
					for(JSONCollection item : json.getCollection("playlist").collectionsIterable()) {
						Playlist.Stream stream = parseStream(item);
						
						if(stream == null) {
							continue;
						}
						
						streams.add(stream);
					}
				}
				
				return new Playlist(streams);
			}
			
			public static final API instance() {
				return instance.value();
			}
			
			@Override
			public final Playlist ofExternal(SourceInfo source) throws Exception {
				String body = Net.queryString(
					"playlist[0][type]", source.type(),
					"playlist[0][id]", source.idec(),
					"requestUrl", REQUEST_URI,
					"requestSource", REQUEST_SOURCE,
					"type", "html",
					"canPlayDRM", "false",
					"streamingProtocol", STREAM_PROTOCOL
				);
				
				String uri;
				try(Response.OfStream response = Web.requestStream(Request.of(ENDPOINT).POST(body))) {
					JSONCollection json = JSON.read(response.stream());
					uri = json.getString("url");
					
					if(uri.startsWith("error")) {
						displayError("media_unavailable");
						return null; // Do not continue
					}
				}
				
				return parsePlaylist(Net.uri(uri));
			}
		}
		
		// The new API that uses the new playlist-vod endpoint (not always supported)
		private static final class V1 implements API {
			
			private static final VarLoader<API> instance = VarLoader.of(V1::new);
			
			private static final URI ENDPOINT;
			private static final String PATH_EXTERNAL;
			
			static {
				ENDPOINT = Net.uri("https://api.ceskatelevize.cz/video/v1/playlist-vod/v1/");
				PATH_EXTERNAL = "stream-data/media/external/%{idec}s";
			}
			
			private V1() {}
			
			private static final Playlist.Stream parseStream(JSONCollection collection, String idec) {
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
				
				// Fix: Sometimes the obtained subtitles files may not actually exist,
				//      therefore we must check the availability and if they do not exist,
				//      use an alternative method.
				for(Entry<MediaLanguage, List<URI>> entry : subtitles.entrySet()) {
					// It is sufficient to check only one of the URIs, if either one of them
					// does not exist all of them do not exist.
					URI subtitleUri = entry.getValue().get(0);
					
					try(Response.OfStream response = Web.peek(Request.of(subtitleUri).HEAD())) {
						// Existing subtitles return status code of 200 and non-existent return 400,
						// but check for 'OK' status rather than 'Not found' status.
						if(response.statusCode() == 200) {
							continue;
						}
						
						// This simulates a call to the V0 fallback without actually using the V0 fallback.
						// It should be fine to do so, since the only non-existent subtitles in the V1 API
						// so far are the hidden subtitles and they are the only ones.
						URI fallbackUri = Net.uri(Utils.format(
							"https://imgct.ceskatelevize.cz/cache/data/ivysilani/subtitles/%{idec_prefix}s/%{idec}s/sub.vtt",
							"idec", idec,
							"idec_prefix", idec.substring(0, 3)
						));
						
						// Just replace the old subtitles with the fallback ones. Do not check their
						// existance to speed things up. If they do not exist, then the situation is
						// actually the same as if we did not do this fallback procedure at all, so
						// it should be fine.
						entry.setValue(List.of(fallbackUri));
					} catch(Exception ex) {
						// Something went wrong, just ignore it since we cannot probably do anything with it
					}
				}
				
				return new Playlist.Stream(uri, subtitles);
			}
			
			private static final Playlist parsePlaylist(URI uri, String idec) throws Exception {
				List<Playlist.Stream> streams = new ArrayList<>();
				
				try(Response.OfStream response = Web.requestStream(Request.of(uri).GET())) {
					JSONCollection json = JSON.read(response.stream());
					
					if(json.has("error") || json.has("message")) {
						return null; // Do not throw exception
					}
					
					for(JSONCollection item : json.getCollection("streams").collectionsIterable()) {
						Playlist.Stream stream = parseStream(item, idec);
						
						if(stream == null) {
							continue;
						}
						
						streams.add(stream);
					}
				}
				
				return new Playlist(streams);
			}
			
			public static final API instance() {
				return instance.value();
			}
			
			@Override
			public final Playlist ofExternal(SourceInfo source) throws Exception {
				return parsePlaylist(Net.resolve(ENDPOINT, Utils.format(PATH_EXTERNAL, "idec", source.idec())), source.idec());
			}
		}
		
		protected static interface API {
			
			Playlist ofExternal(SourceInfo source) throws Exception;
		}
		
		protected static final class Playlist {
			
			private final List<Stream> streams;
			
			private Playlist(List<Stream> streams) {
				this.streams = Objects.requireNonNull(streams);
			}
			
			public List<Stream> streams() { return streams; }
			
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
	
	private static final class IFrameHelper {
		
		private static final String URL_IFRAME;
		
		static {
			URL_IFRAME = "https://player.ceskatelevize.cz/?origin=%{origin}s&IDEC=%{idec}s";
		}
		
		public static final String getURL(String origin, String idec) throws Exception {
			return Utils.format(URL_IFRAME, "origin", origin, "idec", idec);
		}
		
		// Forbid anyone to create an instance of this class
		private IFrameHelper() {
		}
	}
	
	private static final class LinkingData {
		
		private static LinkingData EMPTY;
		
		private final Document document;
		private final JSONCollection data;
		private final String type;
		
		private LinkingData() {
			this.document = null;
			this.data = null;
			this.type = "none";
		}
		
		private LinkingData(Document document, JSONCollection data) {
			this.document = Objects.requireNonNull(document);
			this.data = Objects.requireNonNull(data);
			this.type = data.getString("@type");
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
		
		public final JSONCollection data() {
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
			JSONCollection data = ld.data();
			
			String programName = data.getString("partOfTVSeries.name", "");
			String episodeName = data.getString("name", "");
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
				JSONCollection mediaData = JavaScript.readObject(elData.html());
				JSONCollection meta = mediaData.getCollection("props.pageProps.data.mediaMeta");
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
			return !ld.isEmpty() ? ld.data().getString("headline", defaultValue) : defaultValue;
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
				result = API.getEpisodes(programIDEC, offset, API.MAX_ITEMS_PER_PAGE, seasonId);
				
				JSONCollection items = result.items();
				ctr = items.length() - 1; // Index in reverse
				
				for(JSONCollection item : items.collectionsIterable()) {
					String id = item.getString("id", "");
					episodes.add(id); // Put to the cache
					if(id.equals(episodeId)) index = ctr; // Do not break from the loop due to caching
					--ctr; // Index in reverse
				}
				
				if(total < 0) {
					total = result.total();
				}
				
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
			WebMediaMetadata metadata = WebMediaMetadataExtractor.extract(document);
			
			if(metadata == null) {
				return; // Unable to obtain the ID
			}
			
			SourceInfo source = SourceInfo.ofEpisode(metadata.idec());
			
			// Try to obtain the media title from its linking data and document
			LinkingData ld = LinkingData.from(document).stream()
					.filter((d) -> d.type().equals("TVEpisode"))
					.findFirst().orElseGet(LinkingData::empty);
			String title = LinkingDataTitle.ofTVEpisode(ld, document.title());
			
			URI uri = Net.uri(document.baseUri());
			jobs.add(new ExtractJob(uri, ExtractMethod.SOURCE_INFO, source, title));
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN) && uri.getPath().startsWith(PATH_PREFIX);
		}
	}
	
	private static final class CT_Porady implements CT {
		
		private static final String SUBDOMAIN = "www";
		private static final String PATH_PREFIX = "/porady/";
		private static final String ORIGIN = "iVysilani";
		
		private CT_Porady() {}
		public static final CT_Porady getInstance() { return _Singleton.getInstance(); }
		
		@Override
		public final void getExtractJobs(Document document, List<ExtractJob> jobs) throws Exception {
			WebMediaMetadata metadata = WebMediaMetadataExtractor.extract(document);
			
			if(metadata == null) {
				return; // Unable to obtain the ID
			}
			
			SourceInfo source = SourceInfo.ofEpisode(metadata.idec());
			
			// Try to obtain the media title from its linking data and document
			LinkingData ld = LinkingData.from(document).stream()
				.filter((d) -> d.type().equals("TVEpisode"))
				.findFirst().orElseGet(LinkingData::empty);
			String title = LinkingDataTitle.ofTVEpisode(ld, document.title());
			
			URI uri = Net.uri(IFrameHelper.getURL(ORIGIN, source.idec()));
			jobs.add(new ExtractJob(uri, ExtractMethod.SOURCE_INFO, source, title));
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
				
				if(elURL != null) {
					url = elURL.attr("content") + "/";
				}
			}
			
			if(url == null || url.isBlank()) {
				return; // Invalid URL
			}
			
			Matcher matcherURL = REGEX_SHOW_CODE.matcher(url);
			
			if(!matcherURL.matches()) {
				return; // Cannot obtain the show code
			}
			
			String showCode = matcherURL.group(1);
			String showId = API.getShowId(showCode);
			String showURL = Utils.format(FORMAT_SHOW_URL, "show_id", showId, "show_code", showCode);
			Program program = new Program(Net.uri(showURL), showCode);
			
			// Obtain all the episodes to extract media sources from
			ListTask<Episode> task = ListTask.of((t) -> {
				API.getEpisodes(t, program, WebMediaMetadataExtractor.extract(HTML.from(program.uri())).idec());
			});
			
			task.startAndWait();
			
			CT_Porady ctInstance = CT_Porady.getInstance();
			for(Episode episode : task.list()) {
				ctInstance.getExtractJobs(HTML.from(episode.uri()), jobs);
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
				Matcher matcher = REGEX_PLAYER_HASH.matcher(elScript.html());
				
				if(matcher.find()) {
					playerHash = matcher.group(1);
					break;
				}
			}
			
			if(playerHash != null) {
				// The data-idec attribute contains spaces and slashes, we must remove them manually
				Regex regexSanitizeIdec = Regex.of("[\\s/]+");
				
				// Try to obtain the media title from its linking data and document
				LinkingData ld = LinkingData.from(document).stream()
						.filter((d) -> d.type().equals("Article"))
						.findFirst().orElseGet(LinkingData::empty);
				String title = LinkingDataTitle.ofArticle(ld, document.title());
				
				// Add all the videos on the page
				for(Element elVideo : document.select(SELECTOR_VIDEO)) {
					SourceInfo source = SourceInfo.ofEpisode(regexSanitizeIdec.replaceAll(elVideo.attr("data-idec"), ""));
					URI uri = Net.uri(Utils.format(URL_PLAYER_IFRAME, "url", elVideo.attr("data-url"), "hash", playerHash));
					jobs.add(new ExtractJob(uri, ExtractMethod.SOURCE_INFO, source, title));
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
			List<JSONCollection> items = new ArrayList<>();
			Set<String> ids = new HashSet<>();
			
			// Sometimes the data-ctcomp-data attribute has incorrectly escaped HTML entities,
			// therefore we must fix it manually. Currently, the only issue are quotes.
			Regex regexFixAttr = Regex.of("&u?o?t?;");
			
			for(Element elVideo : document.select(SELECTOR_VIDEO)) {
				JSONCollection data = JSON.read(regexFixAttr.replaceAll(elVideo.attr("data-ctcomp-data"), "\""));
				
				List<JSONCollection> children = new ArrayList<>();
				if(data.hasCollection("items")) { // VideoGallery
					for(JSONCollection item : data.getCollection("items").collectionsIterable()) {
						children.add(item.getCollection("video.data"));
					}
				} else { // Video
					children.add(data);
				}
				
				for(JSONCollection child : children) {
					JSONCollection playlist = child.getCollection("source.playlist");
					
					// Collect individual playlist item's information
					for(JSONCollection item : playlist.collectionsIterable()) {
						String id = item.getString("id", "");
						
						if(ids.contains(id)) {
							continue;
						}
						
						ids.add(id);
						
						JSONCollection newItem = JSONCollection.empty();
						String type = item.getString("type", "vod").toLowerCase();
						newItem.set("type", type);
						newItem.set("playerType", "dash");
						newItem.set("drm", 0);
						newItem.set("canBePlay", 1);
						StreamSupport.stream(item.objectsIterable().spliterator(), false)
									 .filter((o) -> allowedNames.contains(o.name()))
									 .forEach((o) -> newItem.set(o.name(), o));
						
						items.add(newItem);
					}
				}
			}
			
			URI apiUri = Net.uri(API_URL);
			for(JSONCollection item : items) {
				// Construct the HTTP POST body as a JSON object
				JSONCollection coll = JSONCollection.empty();
				JSONCollection array = JSONCollection.emptyArray();
				String contentType = item.getString("type");
				String title = item.getString("title");
				item.remove("type");
				item.remove("title");
				array.add(item);
				coll.set("contentType", contentType);
				coll.set("items", array);
				
				String body = Net.queryString("data", coll.toString(true));
				Request request = Request.of(apiUri).POST(body);
				
				// Do the request to obtain the stream URLs
				try(Response.OfStream response = Web.requestStream(request)) {
					JSONCollection result = JSON.read(response.stream());
					
					for(JSONCollection playlistItem : result.getCollection("RESULT.playlist").collectionsIterable()) {
						SourceInfo source = SourceInfo.ofEpisode(playlistItem.getString("id"));
						URI uri = Net.uri(playlistItem.getString("streamUrls.main"));
						jobs.add(new ExtractJob(uri, ExtractMethod.NONE, source, title));
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
				URI uri = Net.uri(Net.uriFix(elVideo.attr("href")));
				
				QueryArgument query = Net.queryDestruct(uri);
				QueryArgument bonus = query.argumentOf("bonus");
				
				if(bonus == null) {
					continue; // Unsupported
				}
				
				SourceInfo source = SourceInfo.ofBonus(bonus.value());
				jobs.add(new ExtractJob(uri, ExtractMethod.SOURCE_INFO, source, title));
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
			                  .map((l) -> l.data().getString("name"))
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
				SourceInfo source = SourceInfo.ofEpisode(elVideo.attr("data-idec"));
				URI uri = Net.uri(URL + endpoint + '?' + urlData.substring(1));
				jobs.add(new ExtractJob(uri, ExtractMethod.SOURCE_INFO, source, title));
			}
		}
		
		@Override
		public final boolean isCompatible(URI uri) {
			return checkURLSubdomain(uri, SUBDOMAIN);
		}
	}
}