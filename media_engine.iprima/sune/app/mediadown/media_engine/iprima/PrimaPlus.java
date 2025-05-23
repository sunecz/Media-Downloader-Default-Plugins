package sune.app.mediadown.media_engine.iprima;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sune.app.mediadown.concurrent.VarLoader;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.gui.Dialog;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaContainer;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.media.VideoMedia;
import sune.app.mediadown.media.VideoMediaContainer;
import sune.app.mediadown.media.type.SeparatedVideoMediaContainer;
import sune.app.mediadown.media_engine.iprima.IPrimaEngine.Features;
import sune.app.mediadown.media_engine.iprima.IPrimaEngine.IPrima;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.SimpleExecutor;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.ThreadedSpawnableTaskQueue;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper._Singleton;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.JSONSerializable;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.MessageException;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.Nuxt;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.RPC;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONNode;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

final class PrimaPlus implements IPrima {
	
	private static final int FEATURES = Features.ALL;
	private static final String SUBDOMAIN = "www";
	
	PrimaPlus() {}
	public static final PrimaPlus getInstance() { return _Singleton.getInstance(); }
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return API.getPrograms();
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return API.getEpisodes(program);
	}
	
	@Override
	public ListTask<Media> getMedia(IPrimaEngine engine, URI uri) throws Exception {
		return API.getMedia(engine, uri);
	}
	
	@Override
	public boolean isCompatibleSubdomain(String subdomain) {
		return subdomain.equalsIgnoreCase(SUBDOMAIN);
	}
	
	@Override
	public int features() {
		return FEATURES;
	}
	
	private static final class API {
		
		private static final String URL_API_PLAY = "https://api.play-backend.iprima.cz/api/v1/products/play/ids-%{play_id}s";
		private static final String URL_BASE_MOVIE = "https://www.iprima.cz/filmy/";
		private static final String URL_BASE_SERIES = "https://www.iprima.cz/serialy/";
		
		private static final int EXIT_CALLBACK = -1;
		private static final int EXIT_SUCCESS = 0;
		
		private static final int MAX_OFFSET = 1000;
		
		private static final VarLoader<HttpHeaders> sessionHeaders = VarLoader.ofChecked(API::initSessionHeaders);
		
		private API() {
		}
		
		private static final List<String> listGenres() throws Exception {
			final String method = "vdm.frontend.genre.list";
			
			List<String> genres = new ArrayList<>();
			JSONCollection result = RPC.request(method);
			
			if(RPC.isError(result)) {
				throw new MessageException(result.getString("error.message"));
			}
			
			for(JSONCollection genre : result.getCollection("data").collectionsIterable()) {
				String type = genre.getString("type");
				
				if(!type.equals("static")) {
					continue;
				}
				
				genres.add(genre.getString("title"));
			}
			
			return genres;
		}
		
		private static final JSONCollection listNextItems(String stripId, String recommId, int offset, int limit)
				throws Exception {
			final String method = "strip.strip.nextItems.vdm";
			final String deviceType = "WEB";
			
			JSONCollection result = RPC.request(
				method,
				"deviceType", deviceType,
				"stripId", stripId,
				"recommId", recommId,
				"limit", limit,
				"offset", offset
			);
			
			if(RPC.isError(result)) {
				throw new MessageException(result.getString("error.message"));
			}
			
			return result.getCollection("data");
		}
		
		private static final <T> int parseItems(ListTask<T> task, JSONCollection items,
				Set<T> existing, Function<JSONCollection, T> transformer) throws Exception {
			for(JSONCollection item : items.collectionsIterable()) {
				T value = transformer.apply(item);
				
				if(existing.add(value) && !task.add(value)) {
					return EXIT_CALLBACK; // Do not continue
				}
			}
			
			return EXIT_SUCCESS;
		}
		
		private static final Program stripItemToProgram(JSONCollection item) {
			String id = item.getString("id");
			String title = item.getString("title");
			String type = item.getString("type");
			String uri = item.getString("additionals.webUrl", null);
			
			// URI can be null when a non-premium user encounters premium
			// tv show or movie.
			if(uri == null) {
				String slug = item.getString("slug");
				
				switch(type) {
					case "movie":  uri = URL_BASE_MOVIE  + slug; break;
					case "series": uri = URL_BASE_SERIES + slug; break;
				}
			}
			
			return new Program(Net.uri(uri), title, "id", id, "type", type);
		}
		
		private static final List<Season> listSeasons(String programId) throws Exception {
			final String method = "vdm.frontend.season.list.hbbtv";
			
			List<Season> seasons = new ArrayList<>();
			JSONCollection result = RPC.request(
				method,
				"_accessToken", accessToken(),
				"id", programId,
				"pager", Map.of(
					"limit", 999,
					"offset", 0
				)
			);
			
			if(RPC.isError(result)) {
				throw new MessageException(result.getString("error.message"));
			}
			
			for(JSONCollection seasonData : result.getCollection("data").collectionsIterable()) {
				String id = seasonData.getString("id");
				String title = seasonData.getString("title", "");
				int number = seasonData.getInt("seasonNumber");
				seasons.add(new Season(id, title, number));
			}
			
			return seasons;
		}
		
		private static final List<Episode> listEpisodes(Program program, String seasonId) throws Exception {
			final String method = "vdm.frontend.episodes.list.hbbtv";
			
			List<Episode> episodes = new ArrayList<>();
			JSONCollection result = RPC.request(
				method,
				"_accessToken", accessToken(),
				"id", seasonId,
				"pager", Map.of(
					"limit", 999,
					"offset", 0
				),
				"ordering", Map.of(
					"field", "episodeNumber",
					"direction", "desc"
				)
			);
			
			if(RPC.isError(result)) {
				throw new MessageException(result.getString("error.message"));
			}
			
			int numSeason = result.getInt("data.seasonNumber", 0);
			String programTitle = program.title();
			Regex regexEpisodeName = Regex.of("Epizoda\\s+\\d+|^" + Regex.quote(programTitle) + "\\s+\\(\\d+\\)$");
			
			for(JSONCollection episodeData : result.getCollection("data.episodes").collectionsIterable()) {
				JSONNode nodeUpsell = episodeData.get("distribution.upsell", null);
				if(nodeUpsell != null && nodeUpsell.isCollection()) {
					continue; // Not playable with the current account tier
				}
				
				String title = episodeData.getString("title");
				String uri = episodeData.getString("additionals.webUrl");
				int numEpisode = episodeData.getInt("additionals.episodeNumber");
				
				if(regexEpisodeName.matcher(title).matches()) {
					title = null;
				}
				
				Episode episode = new Episode(program, Net.uri(uri), title, numEpisode, numSeason);
				episodes.add(episode);
			}
			
			return episodes;
		}
		
		private static final HttpHeaders initSessionHeaders() throws Exception {
			// It is important to specify the referer, otherwise the response code is 403.
			Map<String, String> mutRequestHeaders = Utils.toMap("Referer", "https://www.iprima.cz/");
			PrimaAuthenticator.SessionData sessionData = logIn();
			
			if(sessionData != null) {
				Utils.merge(mutRequestHeaders, sessionData.requestHeaders());
			}
			
			return Web.Headers.ofSingleMap(mutRequestHeaders);
		}
		
		private static final PrimaAuthenticator.SessionData logIn() throws Exception {
			return PrimaAuthenticator.sessionData();
		}
		
		private static final HttpHeaders logInHeaders() throws Exception {
			return sessionHeaders.valueChecked();
		}
		
		private static final String accessToken() throws Exception {
			return logIn().accessToken();
		}
		
		private static final void displayError(JSONCollection errorInfo) {
			Translation tr = IPrimaHelper.translation().getTranslation("error");
			String message = tr.getSingle("value." + errorInfo.getString("errorCode"));
			tr = tr.getTranslation("media_error");
			Dialog.showContentError(tr.getSingle("title"), tr.getSingle("text"), message);
		}
		
		public static final ListTask<Program> getPrograms() throws Exception {
			return ListTask.of(PrimaCommon.handleErrors((task) -> {
				// Note: Obtaining the list of programs is not stable using this method.
				//       Since there is no currently known other method how to obtain
				//       a stable list of programs, at least for me, we are stuck with
				//       this method.
				Set<Program> existing = Collections.newSetFromMap(new ConcurrentHashMap<>());
				
				final List<String> strips = List.of(
					"8ab51da8-1890-4e78-8770-cbee59a3976a", // Seriály
					"1d0e2451-bcfa-4ecc-a9d7-5d062ad9bf1c", // Seriály (Nejnovější)
					"82bee2e2-32ef-4323-ab1e-5f973bf5f0a6", // Pořady z TV
					"8138baa8-c933-4015-b7ea-17ac7a679da4", // Filmy (Doporučené)
					"3a2c25d8-4384-4945-ba37-ead972fb216d", // Filmy (Nejnovější)
					"7d92a9fa-a958-4d62-9ae9-2e2726c5a348"  // Filmy (Nejsledovanější)
				);
				
				List<StripGroup> stripGroups = new ArrayList<>();
				stripGroups.add(new StripGroup(strips, List.of()));
				
				// Must also obtain the strips filtered by a genre, since some programs
				// are not always visible in the non-filtered list.
				for(String genre : listGenres()) {
					stripGroups.add(new StripGroup(strips, List.of(new Filter("genre", genre))));
				}
				
				final String method = "strip.strip.bulkItems.vdm";
				final String deviceType = "WEB";
				final int perPage = 100;
				final int numMaxThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
				final int numMaxFixed = Math.min(stripGroups.size(), numMaxThreads);
				
				try(
					ThreadedSpawnableTaskQueue<NextItemsTaskArgs, Integer> queue = new ProgramTasks(
						numMaxThreads, task, existing, perPage
					);
					SimpleExecutor<Void> executor = SimpleExecutor.ofFixed(numMaxFixed);
				) {
					for(StripGroup stripGroup : stripGroups) {
						executor.addTask(() -> {
							JSONCollection result = RPC.request(
								method,
								"deviceType", deviceType,
								"stripIds", stripGroup.stripIds(),
								"limit", perPage,
								"filter", stripGroup.filter()
							);
							
							if(RPC.isError(result)) {
								throw new MessageException(result.getString("error.message"));
							}
							
							for(JSONCollection strip : result.getCollection("data").collectionsIterable()) {
								String stripId = strip.name();
								String recommId = strip.getString("recommId");
								boolean hasNextItems = strip.getBoolean("isNextItems");
								JSONCollection items = strip.getCollection("items");
								
								parseItems(task, items, existing, API::stripItemToProgram);
								
								if(hasNextItems) {
									queue.addTask(new NextItemsTaskArgs(stripId, recommId, perPage));
								}
							}
						});
					}
				}
			}));
		}
		
		public static final ListTask<Episode> getEpisodes(Program program)
				throws Exception {
			return ListTask.of(PrimaCommon.handleErrors((task) -> {
				// Handle movies separately since they do not have any episodes
				String type;
				if((type = program.get("type")) != null && type.equals("movie")) {
					Episode episode = new Episode(program, program.uri(), program.title());
					task.add(episode);
					return; // Do not continue
				}
				
				HttpHeaders requestHeaders = logInHeaders();
				String html = Web.request(Request.of(program.uri()).headers(requestHeaders).GET()).body();
				Nuxt nuxt = Nuxt.extract(html);
				
				if(nuxt == null) {
					throw new IllegalStateException("Unable to extract information about episodes");
				}
				
				String dataName = Utils.stream(nuxt.data().collectionsIterable())
					.filter((c) -> c.hasCollection("title"))
					.findFirst().get().name();
				
				String programId = nuxt.get(dataName + ".title.id", null);
				
				for(Season season : listSeasons(programId)) {
					for(Episode episode : listEpisodes(program, season.id())) {
						if(!task.add(episode)) {
							return; // Do not continue
						}
					}
				}
			}));
		}
		
		public static final ListTask<Media> getMedia(IPrimaEngine engine, URI uri) throws Exception {
			return ListTask.of(PrimaCommon.handleErrors((task) -> {
				// Always log in first to ensure the data are correct
				HttpHeaders requestHeaders = logInHeaders();
				
				String html = Web.request(Request.of(uri).headers(requestHeaders).GET()).body();
				Nuxt nuxt = Nuxt.extract(html);
				
				if(nuxt == null) {
					throw new IllegalStateException("Unable to extract information about media content");
				}
				
				String dataName = Utils.stream(nuxt.data().collectionsIterable())
					.filter((c) -> c.hasCollection("content"))
					.findFirst().get().name();
				String videoPlayId = nuxt.get(dataName + ".content.additionals.videoPlayId", null);
				
				if(videoPlayId == null) {
					throw new IllegalStateException("Unable to extract video play ID");
				}
				
				URI configUri = Net.uri(Utils.format(URL_API_PLAY, "play_id", videoPlayId));
				String content = Web.request(Request.of(configUri).headers(requestHeaders).GET()).body();
				
				if(content == null || content.isEmpty()) {
					throw new IllegalStateException("Empty play configuration content");
				}
				
				JSONCollection configData = JSON.read(content);
				
				// Get information for the media title
				String programName = nuxt.get(dataName + ".content.additionals.programTitle", "");
				String numSeason = nuxt.get(dataName + ".content.additionals.seasonNumber", null);
				String numEpisode = nuxt.get(dataName + ".content.additionals.episodeNumber", null);
				String episodeName = nuxt.get(dataName + ".content.title", "");
				
				if(programName.isEmpty()) {
					programName = episodeName;
					episodeName = "";
				}
				
				Regex regexEpisodeName = Regex.of("Epizoda\\s+\\d+|^" + Regex.quote(programName) + "\\s+\\(\\d+\\)$");
				
				if(!episodeName.isEmpty()
						&& regexEpisodeName.matcher(episodeName).matches()) {
					episodeName = "";
				}
				
				if(numSeason != null && numSeason.isEmpty()) numSeason = null;
				if(numEpisode != null && numEpisode.isEmpty()) numEpisode = null;
				
				String title = MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName, false);
				URI sourceURI = uri;
				MediaSource source = MediaSource.of(engine);
				
				JSONCollection streamInfos = JSONCollection.emptyArray();
				List<Media.Builder<?, ?>> subtitles = new ArrayList<>();
				
				for(JSONCollection configItem : configData.collectionsIterable()) {
					// First, check whether there is any error regarding the media source
					JSONCollection errorInfo;
					if((errorInfo = configItem.getCollection("errorResult")) != null) {
						displayError(errorInfo);
						// If one media source has an error, we don't need to continue
						return;
					}
					
					for(JSONCollection streamInfo : configItem.getCollection("streamInfos").collectionsIterable()) {
						streamInfos.add(streamInfo);
					}
					
					if(!configItem.hasCollection("subInfos")) {
						continue; // Skip extraction of subtitles
					}
					
					for(JSONCollection subInfo : configItem.getCollection("subInfos").collectionsIterable()) {
						MediaLanguage subtitleLanguage = MediaLanguage.ofCode(subInfo.getString("lang.key"));
						URI subtitleUri = Net.uri(subInfo.getString("url"));
						MediaFormat subtitleFormat = MediaFormat.fromPath(subtitleUri.toString());
						
						SubtitlesMedia.Builder<?, ?> subtitle = SubtitlesMedia.simple()
							.source(source)
							.uri(subtitleUri)
							.format(subtitleFormat)
							.language(subtitleLanguage);
						
						subtitles.add(subtitle);
					}
				}
				
				for(JSONCollection streamInfo : streamInfos.collectionsIterable()) {
					URI src = Net.uri(streamInfo.getString("url"));
					MediaLanguage language = MediaLanguage.ofCode(streamInfo.getString("lang.key"));
					MediaMetadata metadata = MediaMetadata.empty();
					String type = streamInfo.getString("type").toLowerCase();
					
					if(streamInfo.hasCollection("drmInfo")) {
						JSONCollection drmInfo = streamInfo.getCollection("drmInfo");
						String drmToken = null;
						
						switch(type) {
							case "dash":
								drmToken = Utils.stream(drmInfo.getCollection("modularDrmInfos").collectionsIterable())
									.filter((c) -> c.getString("keySystem").equals("com.widevine.alpha"))
									.map((c) -> c.getString("token"))
									.findFirst().orElse(null);
								break;
							default:
								// Widevine not supported, do not add media sources
								continue;
						}
						
						if(drmToken != null) {
							metadata = MediaMetadata.of("drmToken", drmToken);
						}
					}
					
					List<Media.Builder<?, ?>> media = MediaUtils.createMediaBuilders(
						source, src, sourceURI, title, language, metadata
					);
					
					if(!subtitles.isEmpty()) {
						switch(type) {
							case "hls": {
								// Must wrap the combined video container in a separated video container,
								// otherwise the subtitles will not be downloaded correctly.
								for(ListIterator<Media.Builder<?, ?>> it = media.listIterator(); it.hasNext();) {
									VideoMediaContainer.Builder<?, ?> builder = Utils.cast(it.next());
									
									VideoMedia.Builder<?, ?> video = Utils.cast(
										builder.media().stream()
											.filter((b) -> b.type().is(MediaType.VIDEO))
											.findFirst().get()
									);
									
									// Create a new separated video container with the same properties but
									// with all the available subtitles as well.
									Media.Builder<?, ?> separatedMedia = SeparatedVideoMediaContainer.builder()
										.source(video.source()).uri(video.uri()).quality(video.quality())
										.format(builder.format()).size(video.size()).metadata(video.metadata())
										.bandwidth(video.bandwidth()).codecs(video.codecs())
										.duration(video.duration()).frameRate(video.frameRate())
										.resolution(video.resolution())
										.media(
											   Stream.concat(Stream.of(builder), subtitles.stream())
													 .collect(Collectors.toList())
										);
									
									// Replace the old media builder with the separated one
									it.set(separatedMedia);
								}
								
								break;
							}
							default: {
								media.forEach((m) -> ((MediaContainer.Builder<?, ?>) m).addMedia(subtitles));
								break;
							}
						}
					}
					
					for(Media m : Utils.iterable(media.stream().map(Media.Builder::build).iterator())) {
						if(!task.add(m)) {
							return; // Do not continue
						}
					}
				}
			}));
		}
		
		private static final class Filter implements JSONSerializable {
			
			private final String type;
			private final String value;
			
			public Filter(String type, String value) {
				this.type = type;
				this.value = value;
			}
			
			@Override
			public JSONNode toJSON() {
				JSONCollection json = JSONCollection.empty();
				json.set("type", type);
				json.set("value", value);
				return json;
			}
		}
		
		private static final class StripGroup {
			
			private final List<String> stripIds;
			private final List<Filter> filter;
			
			public StripGroup(List<String> stripIds, List<Filter> filter) {
				this.stripIds = stripIds;
				this.filter = filter;
			}
			
			public List<String> stripIds() {
				return stripIds;
			}
			
			public List<Filter> filter() {
				return filter;
			}
		}
		
		private static final class ProgramTasks extends ThreadedSpawnableTaskQueue<NextItemsTaskArgs, Integer> {
			
			private final ListTask<Program> task;
			private final Set<Program> existing;
			private final int perPage;
			
			public ProgramTasks(int maxThreads, ListTask<Program> task, Set<Program> existing,
					int perPage) {
				super(maxThreads);
				this.task = task;
				this.existing = existing;
				this.perPage = perPage;
			}
			
			@Override
			protected Integer runTask(NextItemsTaskArgs args) throws Exception {
				JSONCollection strip = listNextItems(args.stripId(), args.recommId(), args.offset(), perPage);
				JSONCollection items = strip.getCollection("items");
				
				int result;
				if((result = parseItems(task, items, existing, API::stripItemToProgram)) != EXIT_SUCCESS) {
					return result;
				}
				
				boolean hasNextItems = strip.getBoolean("isNextItems");
				
				if(!shouldShutdown(result) && hasNextItems) {
					int nextOffset = args.offset() + perPage;
					
					if(nextOffset < MAX_OFFSET) {
						addTask(new NextItemsTaskArgs(args.stripId(), args.recommId(), nextOffset));
					}
				}
				
				return result;
			}
			
			@Override
			protected boolean shouldShutdown(Integer val) {
				return val != EXIT_SUCCESS;
			}
		}
		
		private static final class NextItemsTaskArgs {
			
			private final String stripId;
			private final String recommId;
			private final int offset;
			
			public NextItemsTaskArgs(String stripId, String recommId, int offset) {
				this.stripId = stripId;
				this.recommId = recommId;
				this.offset = offset;
			}
			
			public String stripId() {
				return stripId;
			}
			
			public String recommId() {
				return recommId;
			}
			
			public int offset() {
				return offset;
			}
		}
		
		private static final class Season {
			
			private final String id;
			private final String title;
			private final int number;
			
			public Season(String id, String title, int number) {
				this.id = id;
				this.title = title;
				this.number = number;
			}
			
			public String id() { return id; }
			@SuppressWarnings("unused")
			public String title() { return title; }
			@SuppressWarnings("unused")
			public int number() { return number; }
		}
	}
}