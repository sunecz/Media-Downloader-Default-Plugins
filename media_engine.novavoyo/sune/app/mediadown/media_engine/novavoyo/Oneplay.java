package sune.app.mediadown.media_engine.novavoyo;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.concurrent.VarLoader;
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
import sune.app.mediadown.media_engine.novavoyo.Authenticator.AuthenticationData;
import sune.app.mediadown.media_engine.novavoyo.Common.MessageException;
import sune.app.mediadown.media_engine.novavoyo.Common.TranslatableException;
import sune.app.mediadown.media_engine.novavoyo.Connection.Response;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.CheckedFunction;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;
import sune.app.mediadown.util.Ref;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

public final class Oneplay {
	
	private static final int DEFAULT_PARALLELISM = 4; // Currently not configurable
	private static final VarLoader<Oneplay> instance = VarLoader.of(Oneplay::createInstance);
	
	private static final String APP_VERSION = "1.0.18";
	private static final String DEVICE_TYPE_WEB = "web";
	private static final int PROGRAM_LIST_MAX_ITEMS_PER_PAGE = 24; // Capped at 24
	private static final int EPISODE_LIST_MAX_ITEMS_PER_PAGE = 12; // Capped at 12
	
	private final int parallelism;
	private final ConnectionPool connectionPool;
	private volatile boolean wasProfileSelect;
	
	public Oneplay(int parallelism) {
		this.parallelism = parallelism;
		this.connectionPool = new ConnectionPool(parallelism, webDevice());
	}
	
	private static final Oneplay createInstance() {
		return new Oneplay(DEFAULT_PARALLELISM);
	}
	
	public static final Oneplay instance() {
		return instance.value();
	}
	
	private final <T> T openConnection(
		CheckedFunction<Connection, T> function
	) throws Exception {
		try(ConnectionPool.ConnectionItem item = connectionPool.get()) {
			return function.apply(item.connection());
		}
	}

	private final <T> T openConnectionItem(
		CheckedFunction<ConnectionPool.ConnectionItem, T> function
	) throws Exception {
		try(ConnectionPool.ConnectionItem item = connectionPool.get()) {
			return function.apply(item);
		}
	}
	
	private final JSONCollection playbackCapabilities() {
		return JSONCollection.ofObject(
			"protocols", JSONCollection.ofArray(
				JSONObject.ofString("dash"),
				JSONObject.ofString("hls")),
			"drm", JSONCollection.ofArray(
				JSONObject.ofString("widevine")
			),
			"altTransfer", JSONObject.ofString("Unicast"),
			"subtitle", JSONCollection.ofObject(
				"formats", JSONCollection.ofArray(
					JSONObject.ofString("vtt")
				),
				"locations", JSONCollection.ofArray(
					JSONObject.ofString("ExternalTrackLocation")
				)
			),
			"liveSpecificCapabilities", JSONCollection.ofObject(
				"protocols", JSONCollection.ofArray(
					JSONObject.ofString("dash"),
					JSONObject.ofString("hls")
				),
				"drm", JSONCollection.ofArray(
					JSONObject.ofString("widevine")
				),
				"altTransfer", JSONObject.ofString("Unicast"),
				"multipleAudio", JSONObject.ofBoolean(false)
			)
		);
	}
	
	private final void handleErrors(JSONCollection data) throws MessageException {
		if("Error".equals(data.getString("result.status"))) {
			throw new MessageException(data.getString("result.message"));
		}
	}
	
	private final <T> int parseCarouselDataCount(ListTask<T> task, JSONCollection data,
			Function<JSONCollection, T> itemParser) throws Exception {
		JSONCollection items = data.getCollection("carousel.tiles");
		
		if(items == null) {
			return 0;
		}
		
		int count = 0;
		for(JSONCollection item : items.collectionsIterable()) {
			T parsedItem;
			if((parsedItem = itemParser.apply(item)) == null) {
				continue; // Few items currently have no route, ignore them
			}
			
			if(!task.add(parsedItem)) {
				return -1;
			}
			
			++count;
		}
		
		return count;
	}
	
	private final <T> boolean parseCarouselData(ListTask<T> task, JSONCollection data,
			Function<JSONCollection, T> itemParser) throws Exception {
		return parseCarouselDataCount(task, data, itemParser) >= 0;
	}
	
	private final String getContentId(Connection connection, URI uri) throws Exception {
		JSONCollection payload = JSONCollection.ofObject(
			"reason", "start",
			"route", JSONCollection.ofObject(
				"url", JSONObject.ofString(uri.toString())
			)
		);
		
		JSONCollection customData = JSONCollection.ofObject(
			"requireStartAction", JSONObject.ofBoolean(true)
		);
		
		JSONCollection data = connection.request("app.init", payload, customData).data();
		JSONCollection params = data.getCollection("startAction.params.payload");
		String contentId;
		
		return (
			(contentId = params.getString("contentId")) != null
				? contentId
				: params.getString("criteria.contentId")
		);
	}
	
	private final String getEPGContentId(Connection connection, String contentId) throws Exception {
		JSONCollection payload = JSONCollection.ofObject(
			"contentId", JSONObject.ofString(contentId)
		);
		
		JSONCollection customData = JSONCollection.ofObject(
			"shouldBeInModal", JSONObject.ofBoolean(true)
		);
		
		Response response = connection.request(
			"page.content.display",
			payload,
			customData,
			playbackCapabilities()
		);
		
		JSONCollection blocks = response.data().getCollection("layout.blocks");
		
		return Utils.stream(blocks.collectionsIterable())
			.map((c) -> c.getCollection("mainAction.action"))
			.filter(Objects::nonNull)
			.filter((c) -> "content.play".equals(c.getString("call")))
			.map((c) -> c.getString("params.payload.criteria.contentId"))
			.findFirst().orElse(null);
	}
	
	private final ProgramInfo getProgramInfo(Connection connection, URI uri) throws Exception {
		JSONCollection payload = JSONCollection.ofObject(
			"reason", JSONObject.ofString("start"),
			"route", JSONCollection.ofObject(
				"url", JSONObject.ofString(uri.toString())
			)
		);
		
		JSONCollection customData = JSONCollection.ofObject(
			"requireStartAction", JSONObject.ofBoolean(true)
		);
		
		JSONCollection data = connection.request("app.init", payload, customData).data();
		String programId = data.getString("startAction.params.payload.contentId");
		String type = data.getString("startAction.params.contentType");
		String title = data.getString("startAction.route.title");
		
		return programId != null ? new ProgramInfo(programId, type, title) : null;
	}
	
	private final MediaInfo getMediaInfo(JSONCollection data) {
		String title = data.getString("title");
		String type = data.getString("type");
		String programName = null;
		int numSeason = 0;
		int numEpisode = 0;
		
		if("episode".equals(type)) {
			programName = data.getString("show.title");
			
			String strSeason = data.getString("season", "0");
			String strEpisode = data.getString("episodeNumber", "0");
			numSeason = Utils.OfString.asInt(strSeason);
			numEpisode = Utils.OfString.asInt(strEpisode);
			
			// Remove redundant information from the episode title. Variations include
			// text such as "X. díl" or "Díl X".
			if(numEpisode > 0) {
				Regex regex = Regex.of(String.format(
					"(?uis)(?:%1$s\\.?\\s*%2$s|%2$s\\s*%1$s\\.?)(?:\\s*[,-]\\s*)?",
					Regex.quote("" + numEpisode), "d[ií]l|epizoda"
				));
				
				title = regex.replaceAll(title, "");
			}
		} else {
			programName = title;
			title = null;
		}
		
		return new MediaInfo(programName, numSeason, numEpisode, title);
	}
	
	private final void ensureAuthenticated(boolean doProfileSelect) throws Exception {
		// Only do the authentication process if the connection pool is not authenticated
		// or if it's required to be fully authenticated but the full authentication process
		// hasn't taken place yet.
		if(connectionPool.isAuthenticated() && (wasProfileSelect || !doProfileSelect)) {
			return; // Nothing to do
		}
		
		if(!Authenticator.hasCredentials()) {
			throw new TranslatableException("error.incorrect_auth_data");
		}
		
		AuthenticationData authData = openConnection((connection) -> {
			return Authenticator.login(connection, doProfileSelect);
		});
		
		connectionPool.authenticate(authData.authToken());
		Authenticator.rememberAuthenticationData(authData);
		wasProfileSelect = doProfileSelect;
	}
	
	// This method is used to find carousels in the responses for shows returned from WebSocket.
	// Since layout blocks can be nested, carousels may be as deep as multiple levels.
	private final Stream<JSONCollection> blocksFindCarousels(JSONCollection blocks) {
		return Stream.concat(
			// Get carousels from the current level
			Utils.stream(blocks.collectionsIterable())
				.map((c) -> c.getCollection("carousels"))
				.filter(Objects::nonNull)
				.flatMap(Common::asCollectionStream),
			// Recurse, if there are nested layout blocks
			Utils.stream(blocks.collectionsIterable())
				.map((c) -> c.getCollection("layout.blocks"))
				.filter(Objects::nonNull)
				.flatMap(this::blocksFindCarousels)
		);
	}
	
	private final Stream<String> findSeasons(JSONCollection data) {
		return (
			Common.asCollectionStream(data.getCollection("criteria"))
				.filter((c) -> "showSeason".equals(c.getString("template")))
				.flatMap((c) -> Utils.stream(c.getCollection("items").collectionsIterable()))
				.map((i) -> i.getString("criteria"))
		);
	}
	
	private final List<SeasonInfo> getSeasons(Connection connection, String programId)
			throws Exception {
		JSONCollection payload = JSONCollection.ofObject(
			"contentId", JSONObject.ofString(programId)
		);
		
		JSONCollection customData = JSONCollection.ofObject(
			"shouldBeInModal", JSONObject.ofBoolean(true)
		);
		
		Response response = connection.request(
			"page.content.display",
			payload,
			customData,
			playbackCapabilities()
		);
		
		JSONCollection blocks = response.data().getCollection("layout.blocks");
		
		if(blocks == null) {
			return List.of(); // Do not return null
		}
		
		return (
			blocksFindCarousels(blocks)
				.filter((c) -> c.hasCollection("criteria"))
				.flatMap((c) -> findSeasons(c).map((i) -> new SeasonInfo(i, c.getString("id"))))
				.filter((p) -> p.seasonId() != null)
				.collect(Collectors.toList())
		);
	}
	
	private final String getMediaTitle(JSONCollection data) {
		MediaInfo info = getMediaInfo(data.getCollection("playerControl.tracking.contentData"));
		
		return MediaUtils.mediaTitle(
			info.programName(),
			info.seasonNumber(),
			info.episodeNumber(),
			info.title()
		);
	}
	
	private final Device webDevice() {
		return new Device(DEVICE_TYPE_WEB, APP_VERSION, "Unknown", "Windows"); // Common arguments
	}
	
	private final Strategy strategy() {
		return parallelism > 1 ? new ParallelStrategy(parallelism) : new SerialStrategy();
	}
	
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of(Common.handleErrors((task) -> strategy().getPrograms(task)));
	}
	
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return ListTask.of(Common.handleErrors((task) -> strategy().getEpisodes(task, program)));
	}
	
	public ListTask<Media> getMedia(
		MediaEngine engine,
		URI uri,
		Map<String, Object> data
	) throws Exception {
		return ListTask.of(Common.handleErrors((task) -> strategy().getMedia(task, engine, uri)));
	}
	
	public List<Profile> profiles() throws Exception {
		ensureAuthenticated(false);
		return openConnection((connection) -> Authenticator.Profiles.all(connection));
	}
	
	public void dispose() throws Exception {
		connectionPool.close();
	}
	
	private static interface Strategy {
		
		void getPrograms(ListTask<Program> task) throws Exception;
		void getEpisodes(ListTask<Episode> task, Program program) throws Exception;
		void getMedia(ListTask<Media> task, MediaEngine engine, URI uri) throws Exception;
	}
	
	private abstract class StrategyBase implements Strategy {
		
		protected StrategyBase() {
		}
		
		protected final Program parseProgramItem(JSONCollection item) {
			String routeUrl = item.getString("action.route.url");
			
			if(routeUrl == null) {
				return null; // Few items currently have no route, ignore them
			}
			
			String title = item.getString("title");
			URI uri = Net.uri(item.getString("action.route.url"));
			String type = item.getString("tracking.type");
			return new Program(uri, title, "type", type);
		}
		
		protected final Episode parseEpisodeItem(Program program, JSONCollection item) {
			String routeUrl = item.getString("action.route.url");
			
			if(routeUrl == null) {
				return null; // Ignore non-routable episodes
			}
			
			URI uri = Net.uri(item.getString("action.route.url"));
			MediaInfo info = getMediaInfo(item.getCollection("tracking"));
			
			return new Episode(program, uri, info.title(), info.episodeNumber(), info.seasonNumber());
		}
		
		protected abstract void getPrograms(ListTask<Program> task, JSONCollection payload,
				int page, int maxPage, int itemsPerPage) throws Exception;
		protected abstract void getEpisodes(ListTask<Episode> task, Program program,
				List<SeasonInfo> seasons, Function<JSONCollection, Episode> itemParser,
				int itemsPerPage) throws Exception;
		
		public void getPrograms(ListTask<Program> task) throws Exception {
			JSONCollection filters = JSONCollection.ofObject(
				"catalogue", JSONObject.ofString("oneplay")
			);
			
			final int itemsPerPage = PROGRAM_LIST_MAX_ITEMS_PER_PAGE;
			int page = 1;
			String carouselId = "page:25;carousel:277";
			String filterCriterias = "filter:" + Utils.base64Encode(filters.toString(true));
			String sortOption = "title-asc";
			
			JSONCollection pagination = JSONCollection.ofObject(
				"count", JSONObject.ofInt(itemsPerPage),
				"position", JSONObject.ofInt((page - 1) * itemsPerPage + 1)
			);
			
			JSONCollection payload = JSONCollection.ofObject(
				"carouselId", JSONObject.ofString(carouselId),
				"criteria", JSONCollection.ofObject(
					"filterCriterias", JSONObject.ofString(filterCriterias),
					"sortOption", JSONObject.ofString(sortOption)
					),
				"paging", pagination
			);
			
			JSONCollection data = openConnection((connection) -> {
				return connection.request("carousel.display", payload).data();
			});
			
			
			if(!parseCarouselData(task, data, this::parseProgramItem)) {
				return; // Interrupted, do not continue
			}
			
			final int maxPage = data.getInt("carousel.paging.pageCount", 1);
			getPrograms(task, payload, page, maxPage, itemsPerPage);
		}
		
		@Override
		public void getEpisodes(ListTask<Episode> task, Program program) throws Exception {
			ensureAuthenticated(true);
			
			ProgramInfo programInfo = openConnection((connection) -> {
				return getProgramInfo(connection, program.uri());
			});
			if(programInfo == null) {
				throw new IllegalStateException("Program not found");
			}
			
			if("movie".equals(programInfo.type())) {
				task.add(new Episode(program, program.uri(), programInfo.title()));
				return;
			}
			
			final int itemsPerPage = EPISODE_LIST_MAX_ITEMS_PER_PAGE;
			
			List<SeasonInfo> seasons = openConnection((connection) -> {
				return getSeasons(connection, programInfo.programId());
			});
			
			Function<JSONCollection, Episode> itemParser = (item) -> {
				return parseEpisodeItem(program, item);
			};
			
			getEpisodes(task, program, seasons, itemParser, itemsPerPage);
		}
		
		@Override
		public void getMedia(ListTask<Media> task, MediaEngine engine, URI uri) throws Exception {
			ensureAuthenticated(true);
			
			String contentId = openConnection((connection) -> {
				return getContentId(connection, uri);
			});
			
			if(contentId == null) {
				throw new IllegalStateException("Failed to obtain the content ID");
			}
			
			JSONCollection data = openConnection((connection) -> {
				JSONCollection payload = JSONCollection.ofObject(
					"criteria", JSONCollection.ofObject(
						"schema", JSONObject.ofString("ContentCriteria"),
						"contentId", JSONObject.ofString(contentId)
					)
				);
				
				return connection.request(
					"content.play",
					payload,
					null,
					playbackCapabilities()
				).data();
			});
			
			if("Error".equals(data.getString("status"))) {
				if(!"4099".equals(data.getString("code"))) {
					throw new MessageException(data.getString("message"));
				}
				
				String epgContentId = openConnection((connection) -> {
					return getEPGContentId(connection, contentId);
				});
				
				data = openConnection((connection) -> {
					JSONCollection payload = JSONCollection.ofObject(
						"criteria", JSONCollection.ofObject(
							"schema", JSONObject.ofString("ContentCriteria"),
							"contentId", JSONObject.ofString(epgContentId)
						)
					);
					
					return connection.request(
						"content.play",
						payload,
						null,
						playbackCapabilities()
					).data();
				});
			}
			
			MediaSource source = MediaSource.of(engine);
			URI sourceUri = uri;
			String title = getMediaTitle(data);
			JSONCollection streams = data.getCollection("media.stream.assets");
			
			for(JSONCollection stream : streams.collectionsIterable()) {
				MediaFormat format = MediaFormat.fromName(stream.getString("protocol"));
				URI videoUri = Net.uri(stream.getString("src"));
				MediaLanguage language = MediaLanguage.UNKNOWN;
				MediaMetadata metadata = MediaMetadata.empty();
				
				// Consider only supported formats
				if(!format.isAnyOf(MediaFormat.DASH, MediaFormat.M3U8)) {
					continue;
				}
				
				if(stream.hasCollection("drm")) {
					JSONCollection drmInfos = stream.getCollection("drm");
					String drmToken = null;
					
					for(JSONCollection drmInfo : drmInfos.collectionsIterable()) {
						switch(drmInfo.getString("schema")) {
							case "WidevineAcquisition":
								drmToken = drmInfo.getString("drmAuthorization.value");
								break;
							default:
								// Widevine not supported, do not add media sources
								continue;
						}
					}
					
					if(drmToken != null) {
						metadata = MediaMetadata.of("drmToken", drmToken);
					}
				}
				
				List<Media.Builder<?, ?>> media = MediaUtils.createMediaBuilders(
					source, videoUri, sourceUri, title, language, metadata
				);
				
				if(stream.hasCollection("subtitles")) {
					JSONCollection subtitlesArray = stream.getCollection("subtitles");
					
					for(JSONCollection subtitlesInfo : subtitlesArray.collectionsIterable()) {
						String schema = subtitlesInfo.getString("location.schema");
						
						if(!"ExternalTrackLocation".equals(schema)) {
							continue; // Support only external subtitles for now
						}
						
						URI subtitlesUri = Net.uri(subtitlesInfo.getString("location.url"));
						MediaLanguage subtitlesLanguage = MediaLanguage.ofCode(subtitlesInfo.getString("language.code"));
						MediaFormat subtitlesFormat = MediaFormat.ofName(subtitlesInfo.getString("format"));
						
						SubtitlesMedia.Builder<?, ?> subtitles = SubtitlesMedia.simple()
							.source(source)
							.uri(subtitlesUri)
							.format(subtitlesFormat)
							.language(subtitlesLanguage);
						
						media.forEach((m) -> ((MediaContainer.Builder<?, ?>) m).addMedia(subtitles));
					}
				}
				
				for(Media s : Utils.iterable(media.stream().map(Media.Builder::build).iterator())) {
					if(!task.add(s)) {
						return; // Do not continue
					}
				}
			}
		}
	}
	
	private final class ParallelStrategy extends StrategyBase {
		
		private final int parallelism;
		
		public ParallelStrategy(int parallelism) {
			this.parallelism = parallelism;
		}
		
		@Override
		protected void getPrograms(ListTask<Program> task, JSONCollection payload, int page,
				int maxPage, int itemsPerPage) throws Exception {
			JSONCollection[] payloads = new JSONCollection[parallelism];
			
			for(int i = 0, l = payloads.length; i < l; ++i) {
				payloads[i] = payload.copy();
			}
			
			ExecutorService es = Threads.Pools.newFixed(parallelism);
			
			try {
				while(++page <= maxPage) {
					final int p = page;
					es.submit(Common.handleErrors(() -> {
						JSONCollection lData = openConnectionItem((item) -> {
							JSONCollection lPayload = payloads[item.idx()];
							lPayload.set("paging.position", (p - 1) * itemsPerPage + 1);
							Connection con = item.connection();
							return con.request("carousel.display", payload).data();
						});
						
						handleErrors(lData);
						
						List<Program> lp = ListTask.<Program>of((t) -> {
							if(!parseCarouselData(t, lData, this::parseProgramItem)) {
								return; // Interrupted, do not continue
							}
						}).startAndGet();
						
						synchronized(task) {
							task.addAll(lp);
						}
					}));
				}
			} finally {
				es.shutdown();
				es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
			}
		}
		
		@Override
		protected void getEpisodes(ListTask<Episode> task, Program program, List<SeasonInfo> seasons,
				Function<JSONCollection, Episode> itemParser, int itemsPerPage) throws Exception {
			ExecutorService es = Threads.Pools.newFixed(parallelism);
			
			try {
				for(SeasonInfo info : seasons) {
					String carouselId = info.carouselId();
					String seasonId = info.seasonId();
					
					int position = 1;
					JSONCollection pagination = JSONCollection.ofObject(
						"count", JSONObject.ofInt(itemsPerPage),
						"position", JSONObject.ofInt(position)
					);
					
					JSONCollection payload = JSONCollection.ofObject(
						"carouselId", JSONObject.ofString(carouselId),
						"criteria", JSONCollection.ofObject(
							"filterCriterias", JSONObject.ofString(seasonId),
							"sortOption", JSONObject.ofString("DESC")
						),
						"paging", pagination
					);
					
					Set<Integer> seen = new HashSet<>();
					
					// Since we don't know the total number of pages, only the information whether
					// there is a next page or not, we must find the end manually. For this we use
					// binary search, so that we can find the end quickly when the number of pages
					// is high.
					int count = itemsPerPage, lo = 0, hi = count, c;
					
					// First, find the first page that does not have any episodes. The found page
					// may not be the actual last page, but we have an approximate end.
					while(true) {
						pagination.set("position", hi + 1);
						JSONCollection data = openConnection((connection) -> {
							return connection.request("carousel.display", payload).data();
						});
						
						handleErrors(data);
						
						// Also extract the items, since we already visited the page.
						if((c = parseCarouselDataCount(task, data, itemParser)) < 0) {
							return; // Interrupted, do not continue
						}
						
						if(!data.getBoolean("carousel.paging.next")) {
							break; // There are no next pages
						}
						
						seen.add(hi);
						lo = hi;
						hi *= 2;
					}
					
					// Second, find the actual end with any items using the binary search.
					// We have: lo = a page with some items, hi = a page with no items.
					// Therefore the actual end lies in the range <lo, hi).
					while(hi - lo > count) {
						int mid = lo + (hi - lo) / 2;
						
						pagination.set("position", mid + 1);
						JSONCollection data = openConnection((connection) -> {
							return connection.request("carousel.display", payload).data();
						});
						
						handleErrors(data);
						
						// Also extract the items, since we already visited the page.
						if((c = parseCarouselDataCount(task, data, itemParser)) < 0) {
							return; // Interrupted, do not continue
						}
						
						if(c == 0) {
							hi = mid; // No items, end reached
						} else {
							lo = mid; // Items present, still not the end
							seen.add(mid);
						}
					}
					
					hi = lo;
					lo = 0;
					
					JSONCollection[] payloads = new JSONCollection[parallelism];
					Ref.Mutable<Boolean> interrupted = new Ref.Mutable<>(false);
					
					for(int i = 0, l = payloads.length; i < l; ++i) {
						payloads[i] = payload.copy();
					}
					
					// Finally, loop through all pages in the found range but skip all pages we've
					// already extracted items from. Use multiple threads to speed things up.
					for(int off = lo; off <= hi; off += itemsPerPage) {
						if(seen.contains(off)) {
							continue;
						}
						
						final int o = off;
						
						es.submit(Common.handleErrors(() -> {
							if(interrupted.get()) {
								return; // Interrupted, do not continue
							}
							
							JSONCollection lData =  openConnectionItem((item) -> {
								JSONCollection lPayload = payloads[item.idx()];
								lPayload.set("paging.position", o + 1);
								Connection con = item.connection();
								return con.request("carousel.display", payload).data();
							});
							
							handleErrors(lData);
							
							List<Episode> lp = ListTask.<Episode>of((t) -> {
								if(!parseCarouselData(t, lData, itemParser)) {
									interrupted.set(true);
								}
							}).startAndGet();
							
							synchronized(task) {
								task.addAll(lp);
							}
						}));
					}
				}
			} finally {
				es.shutdown();
				es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
			}
		}
	}
	
	private final class SerialStrategy extends StrategyBase {
		
		@Override
		protected void getPrograms(ListTask<Program> task, JSONCollection payload, int page,
				int maxPage, int itemsPerPage) throws Exception {
			while(++page <= maxPage) {
				payload.set("paging.position", (page - 1) * itemsPerPage + 1);
				JSONCollection data = openConnection((connection) -> {
					return connection.request("carousel.display", payload).data();
				});
				
				if(!parseCarouselData(task, data, this::parseProgramItem)) {
					return; // Interrupted, do not continue
				}
			}
		}
		
		@Override
		protected void getEpisodes(ListTask<Episode> task, Program program,
				List<SeasonInfo> seasons, Function<JSONCollection, Episode> itemParser,
				int itemsPerPage) throws Exception {
			JSONCollection data;
			
			for(SeasonInfo info : seasons) {
				String carouselId = info.carouselId();
				String seasonId = info.seasonId();
				
				int position = 1;
				JSONCollection pagination = JSONCollection.ofObject(
					"count", JSONObject.ofInt(itemsPerPage),
					"position", JSONObject.ofInt(position)
				);
				
				JSONCollection payload = JSONCollection.ofObject(
					"carouselId", JSONObject.ofString(carouselId),
					"criteria", JSONCollection.ofObject(
						"filterCriterias", JSONObject.ofString(seasonId),
						"sortOption", JSONObject.ofString("DESC")
					),
					"paging", pagination
				);
				
				do {
					pagination.set("position", position);
					data = openConnection((connection) -> {
						return connection.request("carousel.display", payload).data();
					});
					
					if(!parseCarouselData(task, data, itemParser)) {
						return; // Interrupted, do not continue
					}
					
					position += itemsPerPage;
				} while(data.getBoolean("carousel.paging.next"));
			}
		}
	}
	
	private static final class ProgramInfo {
		
		private final String programId;
		private final String type;
		private final String title;
		
		public ProgramInfo(String programId, String type, String title) {
			this.programId = programId;
			this.type = type;
			this.title = title;
		}
		
		public String programId() { return programId; }
		public String type() { return type; }
		public String title() { return title; }
	}

	private static final class SeasonInfo {
		
		private final String seasonId;
		private final String carouselId;
		
		public SeasonInfo(String seasonId, String carouselId) {
			this.seasonId = seasonId;
			this.carouselId = carouselId;
		}
		
		public String carouselId() { return carouselId; }
		public String seasonId() { return seasonId; }
	}

	private static final class MediaInfo {
		
		private final String programName;
		private final int numSeason;
		private final int numEpisode;
		private final String title;
		
		public MediaInfo(String programName, int numSeason, int numEpisode, String title) {
			this.programName = programName;
			this.numSeason = numSeason;
			this.numEpisode = numEpisode;
			this.title = title;
		}
		
		public String programName() { return programName; }
		public int seasonNumber() { return numSeason; }
		public int episodeNumber() { return numEpisode; }
		public String title() { return title; }
	}
}
