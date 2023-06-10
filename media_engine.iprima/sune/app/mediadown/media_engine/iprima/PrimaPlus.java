package sune.app.mediadown.media_engine.iprima;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.media.Media;
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
import sune.app.mediadown.media_engine.iprima.IPrimaEngine.IPrima;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.ProgramWrapper;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper.ThreadedSpawnableTaskQueue;
import sune.app.mediadown.media_engine.iprima.IPrimaHelper._Singleton;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONNode;
import sune.app.mediadown.util.JSON.JSONObject;
import sune.app.mediadown.util.JSON.JSONType;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

final class PrimaPlus implements IPrima {
	
	private static final String SUBDOMAIN = "www";
	
	private API api;
	
	PrimaPlus() {}
	public static final PrimaPlus getInstance() { return _Singleton.getInstance(); }
	
	private final API api() {
		if(api == null) {
			api = new API(this);
		}
		
		return api;
	}
	
	@Override
	public ListTask<Program> getPrograms(IPrimaEngine engine) throws Exception {
		return api().getPrograms(engine);
	}
	
	@Override
	public ListTask<Episode> getEpisodes(IPrimaEngine engine, Program program) throws Exception {
		return api().getEpisodes(engine, program);
	}
	
	@Override
	public ListTask<Media> getMedia(IPrimaEngine engine, URI uri) throws Exception {
		return api().getMedia(engine, uri);
	}
	
	@Override
	public boolean isCompatibleSubdomain(String subdomain) {
		return subdomain.equalsIgnoreCase(SUBDOMAIN);
	}
	
	private static final class API {
		
		private static final URI URL_ENDPOINT = Net.uri("https://gateway-api.prod.iprima.cz/json-rpc/");
		
		private static final String URL_API_PLAY = "https://api.play-backend.iprima.cz/api/v1/products/play/ids-%{play_id}s";
		private static final String URL_BASE_MOVIE = "https://www.iprima.cz/filmy/";
		private static final String URL_BASE_SERIES = "https://www.iprima.cz/serialy/";
		
		private static final HttpHeaders HEADERS = Web.Headers.ofSingle(
			"Accept", "application/json",
			"Content-Type", "application/json"
		);
		
		private static final int EXIT_CALLBACK = -1;
		private static final int EXIT_SUCCESS = 0;
		
		private static final int MAX_OFFSET = 1000;
		
		private final IPrima iprima;
		private IPrimaAuthenticator.SessionData sessionData;
		private HttpHeaders sessionHeaders;
		
		public API(IPrima iprima) {
			this.iprima = Objects.requireNonNull(iprima);
		}
		
		private static final JSONCollection doRawRequest(String body) throws Exception {
			try(Response.OfStream response = Web.requestStream(Request.of(URL_ENDPOINT).headers(HEADERS).POST(body))) {
				return JSON.read(response.stream()).getCollection("result");
			}
		}
		
		private static final JSONCollection doRequest(String method, Object... params) throws Exception {
			return doRequest(method, Utils.toMap(params));
		}
		
		private static final JSONCollection doRequest(String method, Map<Object, Object> params) throws Exception {
			JSONCollection json = APIRequest.bodyOf(method, params);
			json.setNull("params.profileId");
			return doRawRequest(json.toString(true));
		}
		
		private final List<String> listGenres() throws Exception {
			final String method = "vdm.frontend.genre.list";
			
			List<String> genres = new ArrayList<>();
			JSONCollection result = doRequest(method);
			
			for(JSONCollection genre : result.getCollection("data").collectionsIterable()) {
				String type = genre.getString("type");
				
				if(!type.equals("static")) {
					continue;
				}
				
				genres.add(genre.getString("title"));
			}
			
			return genres;
		}
		
		private final JSONCollection listNextItems(String stripId, String recommId, int offset, int limit)
				throws Exception {
			final String method = "strip.strip.nextItems.vdm";
			final String deviceType = "WEB";
			
			JSONCollection result = doRequest(
				method,
				"deviceType", deviceType,
				"stripId", stripId,
				"recommId", recommId,
				"limit", limit,
				"offset", offset
			);
			
			return result.getCollection("data");
		}
		
		private final <T, P> int parseItems(ListTask<P> task, JSONCollection items, Collection<T> list,
				Function<JSONCollection, T> transformer, Function<T, P> unwrapper) throws Exception {
			for(JSONCollection item : items.collectionsIterable()) {
				T value = transformer.apply(item);
				
				list.add(value);
				if(!task.add(unwrapper.apply(value))) {
					return EXIT_CALLBACK; // Do not continue
				}
			}
			
			return EXIT_SUCCESS;
		}
		
		private final Program stripItemToProgram(JSONCollection item) {
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
			
			return new Program(Net.uri(uri), title, "source", iprima, "id", id, "type", type);
		}
		
		private final ProgramWrapper stripItemToProgramWrapper(JSONCollection item) {
			return new ProgramWrapper(stripItemToProgram(item));
		}
		
		private final List<Season> listSeasons(String programId) throws Exception {
			final String method = "vdm.frontend.season.list.hbbtv";
			
			List<Season> seasons = new ArrayList<>();
			JSONCollection result = doRequest(
				method,
				"_accessToken", accessToken(),
				"id", programId,
				"pager", Map.of(
					"limit", 999,
					"offset", 0
				)
			);
			
			for(JSONCollection seasonData : result.getCollection("data").collectionsIterable()) {
				String id = seasonData.getString("id");
				String title = seasonData.getString("title", "");
				int number = seasonData.getInt("seasonNumber");
				seasons.add(new Season(id, title, number));
			}
			
			return seasons;
		}
		
		private final List<Episode> listEpisodes(Program program, String seasonId) throws Exception {
			final String method = "vdm.frontend.episodes.list.hbbtv";
			
			List<Episode> episodes = new ArrayList<>();
			JSONCollection result = doRequest(
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
				
				title = Utils.replaceUnicodeEscapeSequences(title);
				uri = Utils.replaceUnicodeEscapeSequences(uri);
				
				int numEpisode = episodeData.getInt("additionals.episodeNumber");
				
				StringBuilder fullTitle = new StringBuilder();
				fullTitle.append(programTitle);
				fullTitle.append(" (").append(numSeason).append(". sezóna)");
				fullTitle.append(" - ").append(numEpisode).append(". epizoda");
				
				if(!regexEpisodeName.matcher(title).matches()) {
					fullTitle.append(" - ").append(title);
				}
				
				Episode episode = new Episode(program, Net.uri(uri), fullTitle.toString());
				episodes.add(episode);
			}
			
			return episodes;
		}
		
		private final IPrimaAuthenticator.SessionData logIn() {
			if(sessionData == null) {
				try {
					// Try to log in to the iPrima website using the internal account to have HD sources available.
					sessionData = IPrimaAuthenticator.getSessionData();
				} catch(Exception ex) {
					// Notify the user that the HD sources may not be available due to inability to log in.
					MediaDownloader.error(new IllegalStateException("Unable to log in to the iPrima website.", ex));
				}
			}
			
			return sessionData;
		}
		
		private final HttpHeaders logInHeaders() {
			if(sessionHeaders == null) {
				// It is important to specify the referer, otherwise the response code is 403.
				Map<String, String> mutRequestHeaders = Utils.toMap("Referer", "https://www.iprima.cz/");
				
				IPrimaAuthenticator.SessionData sessionData = logIn();
				if(sessionData != null) {
					Utils.merge(mutRequestHeaders, sessionData.requestHeaders());
					sessionHeaders = Web.Headers.ofSingleMap(mutRequestHeaders);
				}
			}
			
			return sessionHeaders;
		}
		
		private final String accessToken() {
			IPrimaAuthenticator.SessionData sessionData = logIn();
			return sessionData != null ? sessionData.accessToken() : null;
		}
		
		public final ListTask<Program> getPrograms(IPrimaEngine engine) throws Exception {
			return ListTask.of((task) -> {
				final String method = "strip.strip.bulkItems.vdm";
				final String deviceType = "WEB";
				
				Set<ProgramWrapper> programs = new ConcurrentSkipListSet<>();
				
				final List<String> stripsMovies = List.of(
					"8138baa8-c933-4015-b7ea-17ac7a679da4", // Movies (Recommended)
					"8ab51da8-1890-4e78-8770-cbee59a3976a", // Movies (For you)
					"7d92a9fa-a958-4d62-9ae9-2e2726c5a348", // Movies (Most watched)
					"7fe91a63-682b-4c19-a472-5431d528e8ff"  // Movies (From TV)
				);
				
				final List<String> stripsTVShows = List.of(
	  				"bbe97653-dcff-4599-bf6d-5459e8d6eef4", // TV shows
					"82bee2e2-32ef-4323-ab1e-5f973bf5f0a6"  // TV shows (For you)
	  			);
				
				List<StripGroup> stripGroups = new ArrayList<>();
				
				// Must separate movies to genres since there is an offset limit that
				// is otherwise reached.
				for(String genre : listGenres()) {
					stripGroups.add(new StripGroup(stripsMovies, List.of(new Filter("genre", genre))));
				}
				
				stripGroups.add(new StripGroup(stripsTVShows, List.of()));
				
				final int numMaxProcessors = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
				final int perPage = 64;
				
				ThreadedSpawnableTaskQueue<NextItemsTaskArgs, Integer> queue
						= (new ThreadedSpawnableTaskQueue<>(numMaxProcessors) {
					
					@Override
					protected Integer runTask(NextItemsTaskArgs args) throws Exception {
						JSONCollection strip = listNextItems(args.stripId(), args.recommId(), args.offset(), perPage);
						JSONCollection items = strip.getCollection("items");
						
						int result;
						if((result = parseItems(task, items, programs, API.this::stripItemToProgramWrapper,
								ProgramWrapper::program)) != EXIT_SUCCESS) {
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
				});
				
				ExecutorService executor = Threads.Pools.newFixed(Math.min(stripGroups.size(), numMaxProcessors));
				
				for(StripGroup stripGroup : stripGroups) {
					executor.submit(Utils.callable(() -> {
						JSONCollection result = doRequest(
							method,
							"deviceType", deviceType,
							"stripIds", stripGroup.stripIds(),
							"limit", perPage,
							"filter", stripGroup.filter()
						);
						
						for(JSONCollection strip : result.getCollection("data").collectionsIterable()) {
							String stripId = strip.name();
							String recommId = strip.getString("recommId");
							boolean hasNextItems = strip.getBoolean("isNextItems");
							JSONCollection items = strip.getCollection("items");
							
							parseItems(task, items, programs, this::stripItemToProgramWrapper, ProgramWrapper::program);
							
							if(hasNextItems) {
								queue.addTask(new NextItemsTaskArgs(stripId, recommId, perPage));
							}
						}
					}));
				}
				
				executor.shutdown();
				executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
				queue.await();
				
				for(ProgramWrapper wrapper : programs) {
					if(!task.add(wrapper.program())) {
						return;
					}
				}
			});
		}
		
		public final ListTask<Episode> getEpisodes(IPrimaEngine engine, Program program) throws Exception {
			return ListTask.of((task) -> {
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
				
				String programId = nuxt.getResolved(dataName + ".title.id");
				
				for(Season season : listSeasons(programId)) {
					for(Episode episode : listEpisodes(program, season.id())) {
						if(!task.add(episode)) {
							return; // Do not continue
						}
					}
				}
			});
		}
		
		public final ListTask<Media> getMedia(IPrimaEngine engine, URI uri) throws Exception {
			return ListTask.of((task) -> {
				// Always log in first to ensure the data are correct
				HttpHeaders requestHeaders = logInHeaders();
				
				String html = Web.request(Request.of(uri).GET()).body();
				Nuxt nuxt = Nuxt.extract(html);
				
				if(nuxt == null) {
					throw new IllegalStateException("Unable to extract information about media content");
				}
				
				String dataName = Utils.stream(nuxt.data().collectionsIterable())
					.filter((c) -> c.hasCollection("content"))
					.findFirst().get().name();
				String videoPlayId = nuxt.getResolved(dataName + ".content.additionals.videoPlayId");
				
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
				String programName = nuxt.getResolved(dataName + ".content.additionals.programTitle", "");
				String numSeason = nuxt.getResolved(dataName + ".content.additionals.seasonNumber", null);
				String numEpisode = nuxt.getResolved(dataName + ".content.additionals.episodeNumber", null);
				String episodeName = nuxt.getResolved(dataName + ".content.title", "");
				
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
					List<Media.Builder<?, ?>> media = MediaUtils.createMediaBuilders(
						source, src, sourceURI, title, language, MediaMetadata.empty()
					);
					
					if(!subtitles.isEmpty()) {
						String type = streamInfo.getString("type");
						
						switch(type.toLowerCase()) {
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
								media.forEach((m) -> MediaUtils.appendMedia(Utils.cast(m), subtitles));
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
			});
		}
		
		private static interface JSONSerializable {
			
			JSONNode toJSON();
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
		
		private static final class Nuxt {
			
			private final JSONCollection data;
			private final Map<String, String> args;
			private final boolean isPremium;
			
			private Nuxt(JSONCollection data, Map<String, String> args, boolean isPremium) {
				this.data = Objects.requireNonNull(data);
				this.args = Objects.requireNonNull(args);
				this.isPremium = isPremium;
			}
			
			/**
			 * @return 0 if an opening bracket, 1 if a closing bracket, -1 if neither.
			 */
			private static final int isBracketChar(int c) {
				return c <= ')' ? ((c = c - '(') >= 0           ? 0b000 | (c)      : -1) :
					   c <= ']' ? ((c = c - '[') >= 0 && c != 1 ? 0b010 | (c >> 1) : -1) :
					   c <= '}' ? ((c = c - '{') >= 0 && c != 1 ? 0b100 | (c >> 1) : -1) :
					   -1;
			}
			
			private static final boolean isVarContentClosingChar(int c) {
				return c == ';' || c == '<'; // '<' for HTML script tag
			}
			
			private static final int varContentEndIndex(String string, int start) {
				int end = string.length();
				
				int[] brackets = new int[3];
				boolean escaped = false;
				boolean sq = false;
				boolean dq = false;
				
				loop:
				for(int i = start, l = string.length(), c, v; i < l; ++i) {
					c = string.codePointAt(i);
					
					if(escaped) {
						escaped = false;
					} else {
						switch(c) {
							case '\\': escaped = true; break;
							case '\"': if(!sq) dq = !dq; break;
							case '\'': if(!dq) sq = !sq; break;
							default:
								if(!sq && !dq) {
									if((v = isBracketChar(c)) >= 0) {
										// Adds either 1 (opening) or -1 (closing) to the bracket counter
										brackets[v >> 1] += ((2 - ((v & 0b1) + 1)) << 1) - 1;
									} else if(isVarContentClosingChar(c)
													&& brackets[0] == 0
													&& brackets[1] == 0
													&& brackets[2] == 0) {
										end = i;
										break loop;
									}
								}
								break;
						}
					}
				}
				
				return end;
			}
			
			public static final Nuxt extract(String html) {
				int indexVar;
				if((indexVar = html.indexOf("window.__NUXT__")) < 0) {
					throw new IllegalStateException("Nuxt object does not exist");
				}
				
				// Find the end of the Nuxt variable's content, so that we can extract information
				// only from the actual data and not somewhere else.
				final int end = varContentEndIndex(html, indexVar);
				
				int indexReturn;
				if((indexReturn = html.indexOf("return", indexVar)) < 0) {
					throw new IllegalStateException("Nuxt object has an unsupported format");
				}
				
				String content = Utils.bracketSubstring(html, '{', '}', false, indexReturn, end);
				
				// Also parse the function arguments since some values are present there
				String fnArgsNames = Utils.bracketSubstring(html, '(', ')', false, html.indexOf("function", indexVar), end);
				String fnArgsValues = Utils.bracketSubstring(html, '(', ')', false, indexReturn + content.length(), end);
				fnArgsNames = fnArgsNames.substring(1, fnArgsNames.length() - 1); // Remove brackets
				fnArgsValues = fnArgsValues.substring(1, fnArgsValues.length() - 1); // Remove brackets
				// Source: https://stackoverflow.com/a/1757107
				Regex regexComma = Regex.of(",(?=(?:[^\\\"]*\\\"[^\\\"]*\\\")*[^\\\"]*$)");
				
				Map<String, String> mapArgs = Utils.zip(
					regexComma.splitAsStream(fnArgsNames),
					regexComma.splitAsStream(fnArgsValues),
					Map::entry
				).collect(Collectors.toMap(Entry::getKey, Entry::getValue));
				
				// Must remove JavaScript function calls and object instantiation from the content,
				// otherwise it cannot be parsed correctly.
				Regex regexJSCall = Regex.of(":\\s*[^\\(\\,\\\"]+\\((?:(?<!\\\")[^\\,])+(?<!\\\"),");
				content = regexJSCall.replaceAll(content, "\"\"");
				
				// Cannot use JavaScript.readObject since the names are not quoted
				JSONCollection object = JavaScript.readObject(content);
				JSONCollection data = object.getCollection("data");
				JSONCollection state = object.getCollection("state");
				
				String role = Utils.stream(state.collectionsIterable())
					.filter((c) -> c.hasObject("role"))
					.findFirst().orElseGet(JSONCollection::empty)
					.getString("role", "").toLowerCase();
				boolean isPremium = role.equals("premium");
				
				return new Nuxt(data, mapArgs, isPremium);
			}
			
			@SuppressWarnings("unused")
			public JSONCollection getCollection(String name) {
				return data.getCollection(name);
			}
			
			private final JSONCollection resolveCollection(JSONCollection original) {
				boolean isArray = original.type() == JSONType.ARRAY;
				JSONCollection resolved = JSONCollection.empty(isArray);
				
				if(isArray) {
					for(JSONNode node : original) {
						if(node.isCollection()) {
							resolved.add(resolveCollection((JSONCollection) node));
						} else {
							JSONObject object = (JSONObject) node;
							
							if(object.type() == JSONType.UNKNOWN) {
								resolved.add(resolve(object.stringValue()));
							} else {
								resolved.add(object);
							}
						}
					}
				} else {
					for(JSONNode node : original) {
						String name = node.name();
						
						if(node.isCollection()) {
							resolved.set(name, resolveCollection((JSONCollection) node));
						} else {
							JSONObject object = (JSONObject) node;
							
							if(object.type() == JSONType.UNKNOWN) {
								resolved.set(name, resolve(object.stringValue()));
							} else {
								resolved.set(name, object);
							}
						}
					}
				}
				
				return resolved;
			}
			
			@SuppressWarnings("unused")
			public JSONCollection getResolvedCollection(String name) {
				return resolveCollection(data.getCollection(name));
			}
			
			public String resolve(String value) {
				String val;
				if((val = args.get(value)) != null) {
					return Utils.unquote(val);
				}
				
				return value;
			}
			
			public String getResolved(String name) {
				return getResolved(name, null);
			}
			
			public String getResolved(String name, String defaultValue) {
				JSONObject object = data.getObject(name, null);
				
				if(object == null) {
					return defaultValue;
				}
				
				String value = object.stringValue();
				
				switch(object.type()) {
					case STRING_UNQUOTED:
					case UNKNOWN:
						value = args.get(value);
						break;
					default:
						// Do nothing
						break;
				}
				
				return Utils.unquote(value);
			}
			
			public JSONCollection data() {
				return data;
			}
			
			@SuppressWarnings("unused")
			public boolean isPremium() {
				return isPremium;
			}
		}
		
		private static final class APIRequest {
			
			private APIRequest() {
			}
			
			private static final void setHeader(JSONCollection json) {
				json.set("id", "1");
				json.set("jsonrpc", "2.0");
			}
			
			private static final void setPrimitiveParam(JSONCollection parent, String name, Object value) {
				if(value == null) {
					parent.setNull(name);
					return;
				}
				
				if(value instanceof JSONSerializable) {
					JSONNode json = ((JSONSerializable) value).toJSON();
					
					if(json instanceof JSONCollection) parent.set(name, (JSONCollection) json); else
					if(json instanceof JSONObject)     parent.set(name, (JSONObject) json);
					else                              parent.setNull(name);
					
					return;
				}
				
				Class<?> clazz = value.getClass();
				
				if(clazz == Boolean.class) 	 parent.set(name, (Boolean) value); else
		        if(clazz == Byte.class) 	 parent.set(name, (Byte) value); else
		        if(clazz == Character.class) parent.set(name, (Character) value); else
		        if(clazz == Short.class) 	 parent.set(name, (Short) value); else
		        if(clazz == Integer.class) 	 parent.set(name, (Integer) value); else
		        if(clazz == Long.class) 	 parent.set(name, (Long) value); else
		        if(clazz == Float.class) 	 parent.set(name, (Float) value); else
		        if(clazz == Double.class) 	 parent.set(name, (Double) value);
		        else                         parent.set(name, String.valueOf(value));
			}
			
			private static final void addPrimitiveParam(JSONCollection parent, Object value) {
				if(value == null) {
					parent.addNull();
					return;
				}
				
				if(value instanceof JSONSerializable) {
					JSONNode json = ((JSONSerializable) value).toJSON();
					
					if(json instanceof JSONCollection) parent.add((JSONCollection) json); else
					if(json instanceof JSONObject)     parent.add((JSONObject) json);
					else                              parent.addNull();
					
					return;
				}
				
				Class<?> clazz = value.getClass();
				
				if(clazz == Boolean.class) 	 parent.add((Boolean) value); else
		        if(clazz == Byte.class) 	 parent.add((Byte) value); else
		        if(clazz == Character.class) parent.add((Character) value); else
		        if(clazz == Short.class) 	 parent.add((Short) value); else
		        if(clazz == Integer.class) 	 parent.add((Integer) value); else
		        if(clazz == Long.class) 	 parent.add((Long) value); else
		        if(clazz == Float.class) 	 parent.add((Float) value); else
		        if(clazz == Double.class) 	 parent.add((Double) value);
		        else                         parent.add(String.valueOf(value));
			}
			
			private static final JSONCollection constructMap(Map<?, ?> map) {
				JSONCollection ssdMap = JSONCollection.empty();
				
				for(Entry<?, ?> entry : map.entrySet()) {
					setObjectParam(ssdMap, String.valueOf(entry.getKey()), entry.getValue());
				}
				
				return ssdMap;
			}
			
			private static final JSONCollection constructArray(List<?> list) {
				JSONCollection ssdArray = JSONCollection.emptyArray();
				
				for(Object item : list) {
					addObjectParam(ssdArray, item);
				}
				
				return ssdArray;
			}
			
			private static final void addObjectParam(JSONCollection parent, Object value) {
				if(value instanceof Map) {
					parent.add(constructMap((Map<?, ?>) value));
				} else if(value instanceof List) {
					parent.add(constructArray((List<?>) value));
				} else {
					addPrimitiveParam(parent, value);
				}
			}
			
			private static final void setObjectParam(JSONCollection parent, String name, Object value) {
				if(value instanceof Map) {
					parent.set(name, constructMap((Map<?, ?>) value));
				} else if(value instanceof List) {
					parent.set(name, constructArray((List<?>) value));
				} else {
					setPrimitiveParam(parent, name, value);
				}
			}
			
			private static final JSONCollection paramsOf(Map<Object, Object> params) {
				JSONCollection json = JSONCollection.empty();
				
				for(Entry<Object, Object> entry : params.entrySet()) {
					setObjectParam(json, String.valueOf(entry.getKey()), entry.getValue());
				}
				
				return json;
			}
			
			public static final JSONCollection bodyOf(String method, Map<Object, Object> params) {
				JSONCollection json = JSONCollection.empty();
				setHeader(json);
				json.set("method", method);
				json.set("params", paramsOf(params));
				return json;
			}
		}
	}
}