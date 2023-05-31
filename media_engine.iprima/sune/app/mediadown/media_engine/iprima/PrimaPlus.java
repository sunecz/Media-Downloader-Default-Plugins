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
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDF;
import sune.util.ssdf2.SSDNode;
import sune.util.ssdf2.SSDObject;

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
		
		public API(IPrima iprima) {
			this.iprima = Objects.requireNonNull(iprima);
		}
		
		private static final SSDCollection doRawRequest(String body) throws Exception {
			try(Response.OfStream response = Web.requestStream(Request.of(URL_ENDPOINT).headers(HEADERS).POST(body))) {
				return JSON.read(response.stream()).getDirectCollection("result");
			}
		}
		
		private static final SSDCollection doRequest(String method, Object... params) throws Exception {
			return doRequest(method, Utils.toMap(params));
		}
		
		private static final SSDCollection doRequest(String method, Map<Object, Object> params) throws Exception {
			SSDCollection json = APIRequest.bodyOf(method, params);
			json.setNull("params.profileId");
			return doRawRequest(json.toJSON(true));
		}
		
		private final List<String> listGenres() throws Exception {
			final String method = "vdm.frontend.genre.list";
			
			List<String> genres = new ArrayList<>();
			SSDCollection result = doRequest(method);
			
			for(SSDCollection genre : result.getDirectCollection("data").collectionsIterable()) {
				String type = genre.getDirectString("type");
				
				if(!type.equals("static")) {
					continue;
				}
				
				genres.add(genre.getDirectString("title"));
			}
			
			return genres;
		}
		
		private final SSDCollection listNextItems(String stripId, String recommId, int offset, int limit)
				throws Exception {
			final String method = "strip.strip.nextItems.vdm";
			final String deviceType = "WEB";
			
			SSDCollection result = doRequest(
				method,
				"deviceType", deviceType,
				"stripId", stripId,
				"recommId", recommId,
				"limit", limit,
				"offset", offset
			);
			
			return result.getDirectCollection("data");
		}
		
		private final <T, P> int parseItems(ListTask<P> task, SSDCollection items, Collection<T> list,
				Function<SSDCollection, T> transformer, Function<T, P> unwrapper) throws Exception {
			for(SSDCollection item : items.collectionsIterable()) {
				T value = transformer.apply(item);
				
				list.add(value);
				if(!task.add(unwrapper.apply(value))) {
					return EXIT_CALLBACK; // Do not continue
				}
			}
			
			return EXIT_SUCCESS;
		}
		
		private final Program stripItemToProgram(SSDCollection item) {
			String id = item.getDirectString("id");
			String title = item.getDirectString("title");
			String type = item.getDirectString("type");
			String uri = item.getString("additionals.webUrl", null);
			
			// URI can be null when a non-premium user encounters premium
			// tv show or movie.
			if(uri == null) {
				String slug = item.getDirectString("slug");
				
				switch(type) {
					case "movie":  uri = URL_BASE_MOVIE  + slug; break;
					case "series": uri = URL_BASE_SERIES + slug; break;
				}
			}
			
			return new Program(Net.uri(uri), title, "source", iprima, "id", id, "type", type);
		}
		
		private final ProgramWrapper stripItemToProgramWrapper(SSDCollection item) {
			return new ProgramWrapper(stripItemToProgram(item));
		}
		
		private final HttpHeaders logIn() {
			// It is important to specify the referer, otherwise the response code is 403.
			Map<String, String> mutRequestHeaders = Utils.toMap("Referer", "https://www.iprima.cz/");
			try {
				// Try to log in to the iPrima website using the internal account to have HD sources available.
				IPrimaAuthenticator.SessionData sessionData = IPrimaAuthenticator.getSessionData();
				Utils.merge(mutRequestHeaders, sessionData.requestHeaders());
			} catch(Exception ex) {
				// Notify the user that the HD sources may not be available due to inability to log in.
				MediaDownloader.error(new IllegalStateException("Unable to log in to the iPrima website.", ex));
			}
			
			return Web.Headers.ofSingleMap(mutRequestHeaders);
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
						SSDCollection strip = listNextItems(args.stripId(), args.recommId(), args.offset(), perPage);
						SSDCollection items = strip.getDirectCollection("items");
						
						int result;
						if((result = parseItems(task, items, programs, API.this::stripItemToProgramWrapper,
								ProgramWrapper::program)) != EXIT_SUCCESS) {
							return result;
						}
						
						boolean hasNextItems = strip.getDirectBoolean("isNextItems");
						
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
						SSDCollection result = doRequest(
							method,
							"deviceType", deviceType,
							"stripIds", stripGroup.stripIds(),
							"limit", perPage,
							"filter", stripGroup.filter()
						);
						
						for(SSDCollection strip : result.getDirectCollection("data").collectionsIterable()) {
							String stripId = strip.getName();
							String recommId = strip.getDirectString("recommId");
							boolean hasNextItems = strip.getDirectBoolean("isNextItems");
							SSDCollection items = strip.getDirectCollection("items");
							
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
				
				HttpHeaders requestHeaders = logIn();
				String html = Web.request(Request.of(program.uri()).headers(requestHeaders).GET()).body();
				Nuxt nuxt = Nuxt.extract(html);
				
				if(nuxt == null) {
					throw new IllegalStateException("Unable to extract information about episodes");
				}
				
				String dataName = Utils.stream(nuxt.data().collectionsIterable())
					.filter((c) -> c.hasCollection("title"))
					.findFirst().get().getName();
				
				SSDCollection seasons = nuxt.getCollection(dataName + ".title.seasons");
				String programTitle = program.title();
				
				Regex regexEpisodeName = Regex.of("Epizoda\\s+\\d+|^" + Regex.quote(programTitle) + "\\s+\\(\\d+\\)$");
				
				List<SSDCollection> collSeasons = List.copyOf(seasons.collections());
				
				int numSeason = collSeasons.size();
				for(ListIterator<SSDCollection> itSeason = collSeasons.listIterator(numSeason);
						itSeason.hasPrevious(); --numSeason) {
					SSDCollection seasonData = itSeason.previous();
					List<SSDCollection> collEpisodes = List.copyOf(seasonData.getDirectCollection("episodes").collections());
					
					int numEpisode = collEpisodes.size();
					for(ListIterator<SSDCollection> itEpisode = collEpisodes.listIterator(numEpisode);
							itEpisode.hasPrevious(); --numEpisode) {
						SSDCollection episodeData = itEpisode.previous();
						
						SSDNode nodeUpsell = episodeData.get("distribution.upsell", null);
						if(nodeUpsell != null && nodeUpsell.isCollection()) {
							continue; // Not playable with the current account tier
						}
						
						String title = nuxt.resolve(episodeData.getDirectString("title"));
						String uri = nuxt.resolve(episodeData.getString("additionals.webUrl"));
						
						title = Utils.replaceUnicodeEscapeSequences(title);
						uri = Utils.replaceUnicodeEscapeSequences(uri);
						
						StringBuilder fullTitle = new StringBuilder();
						fullTitle.append(programTitle);
						fullTitle.append(" (").append(numSeason).append(". sezóna)");
						fullTitle.append(" - ").append(numEpisode).append(". epizoda");
						
						if(!regexEpisodeName.matcher(title).matches()) {
							fullTitle.append(" - ").append(title);
						}
						
						Episode episode = new Episode(program, Net.uri(uri), fullTitle.toString());
						
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
				HttpHeaders requestHeaders = logIn();
				
				String html = Web.request(Request.of(uri).GET()).body();
				Nuxt nuxt = Nuxt.extract(html);
				
				if(nuxt == null) {
					throw new IllegalStateException("Unable to extract information about media content");
				}
				
				String dataName = Utils.stream(nuxt.data().collectionsIterable())
					.filter((c) -> c.hasCollection("content"))
					.findFirst().get().getName();
				String videoPlayId = nuxt.getResolved(dataName + ".content.additionals.videoPlayId");
				
				if(videoPlayId == null) {
					throw new IllegalStateException("Unable to extract video play ID");
				}
				
				URI configUri = Net.uri(Utils.format(URL_API_PLAY, "play_id", videoPlayId));
				String content = Web.request(Request.of(configUri).headers(requestHeaders).GET()).body();
				
				if(content == null || content.isEmpty()) {
					throw new IllegalStateException("Empty play configuration content");
				}
				
				SSDCollection configData = JSON.read(content);
				
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
				
				SSDCollection streamInfos = SSDCollection.emptyArray();
				List<Media.Builder<?, ?>> subtitles = new ArrayList<>();
				
				for(SSDCollection configItem : configData.collectionsIterable()) {
					for(SSDCollection streamInfo : configItem.getDirectCollection("streamInfos").collectionsIterable()) {
						streamInfos.add(streamInfo);
					}
					
					if(!configItem.hasDirectCollection("subInfos")) {
						continue; // Skip extraction of subtitles
					}
					
					for(SSDCollection subInfo : configItem.getDirectCollection("subInfos").collectionsIterable()) {
						MediaLanguage subtitleLanguage = MediaLanguage.ofCode(subInfo.getString("lang.key"));
						URI subtitleUri = Net.uri(subInfo.getDirectString("url"));
						MediaFormat subtitleFormat = MediaFormat.fromPath(subtitleUri.toString());
						
						SubtitlesMedia.Builder<?, ?> subtitle = SubtitlesMedia.simple()
							.source(source)
							.uri(subtitleUri)
							.format(subtitleFormat)
							.language(subtitleLanguage);
						
						subtitles.add(subtitle);
					}
				}
				
				for(SSDCollection streamInfo : streamInfos.collectionsIterable()) {
					URI src = Net.uri(streamInfo.getDirectString("url"));
					MediaLanguage language = MediaLanguage.ofCode(streamInfo.getString("lang.key"));
					List<Media.Builder<?, ?>> media = MediaUtils.createMediaBuilders(
						source, src, sourceURI, title, language, MediaMetadata.empty()
					);
					
					if(!subtitles.isEmpty()) {
						String type = streamInfo.getDirectString("type");
						
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
			
			SSDNode toJSON();
		}
		
		private static final class Filter implements JSONSerializable {
			
			private final String type;
			private final String value;
			
			public Filter(String type, String value) {
				this.type = type;
				this.value = value;
			}
			
			@Override
			public SSDNode toJSON() {
				SSDCollection json = SSDCollection.empty();
				json.setDirect("type", type);
				json.setDirect("value", value);
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
		
		private static final class Nuxt {
			
			private final SSDCollection data;
			private final Map<String, String> args;
			private final boolean isPremium;
			
			private Nuxt(SSDCollection data, Map<String, String> args, boolean isPremium) {
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
				content = regexJSCall.replaceAll(content, (m) -> "\"\"");
				
				// Cannot use JavaScript.readObject since the names are not quoted
				SSDCollection object = SSDF.read(Utils.prefixUnicodeEscapeSequences(content, "\\\\\\"));
				SSDCollection data = object.getDirectCollection("data");
				SSDCollection state = object.getDirectCollection("state");
				
				String role = Utils.stream(state.collectionsIterable())
					.filter((c) -> c.hasDirectObject("role"))
					.findFirst().orElseGet(SSDCollection::empty)
					.getDirectString("role", "").toLowerCase();
				boolean isPremium = role.equals("premium");
				
				return new Nuxt(data, mapArgs, isPremium);
			}
			
			public SSDCollection getCollection(String name) {
				return data.getCollection(name);
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
				SSDObject object = data.getObject(name, null);
				
				if(object == null) {
					return defaultValue;
				}
				
				String value = object.getValue().stringValue();
				
				switch(object.getType()) {
					case STRING_VAR:
					case UNKNOWN:
						value = args.get(value);
						break;
					default:
						// Do nothing
						break;
				}
				
				return Utils.unquote(value);
			}
			
			public SSDCollection data() {
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
			
			private static final void setHeader(SSDCollection json) {
				json.set("id", "1");
				json.set("jsonrpc", "2.0");
			}
			
			private static final void setPrimitiveParam(SSDCollection parent, String name, Object value) {
				if(value == null) {
					parent.setNull(name);
					return;
				}
				
				if(value instanceof JSONSerializable) {
					SSDNode json = ((JSONSerializable) value).toJSON();
					
					if(json instanceof SSDCollection) parent.set(name, (SSDCollection) json); else
					if(json instanceof SSDObject)     parent.set(name, (SSDObject) json);
					else                              parent.setNull(name);
					
					return;
				}
				
				Class<?> clazz = value.getClass();
				
				if(clazz == Boolean.class) 	 parent.setDirect(name, (Boolean) value); else
		        if(clazz == Byte.class) 	 parent.setDirect(name, (Byte) value); else
		        if(clazz == Character.class) parent.setDirect(name, (Character) value); else
		        if(clazz == Short.class) 	 parent.setDirect(name, (Short) value); else
		        if(clazz == Integer.class) 	 parent.setDirect(name, (Integer) value); else
		        if(clazz == Long.class) 	 parent.setDirect(name, (Long) value); else
		        if(clazz == Float.class) 	 parent.setDirect(name, (Float) value); else
		        if(clazz == Double.class) 	 parent.setDirect(name, (Double) value);
		        else                         parent.setDirect(name, String.valueOf(value));
			}
			
			private static final void addPrimitiveParam(SSDCollection parent, Object value) {
				if(value == null) {
					parent.addNull();
					return;
				}
				
				if(value instanceof JSONSerializable) {
					SSDNode json = ((JSONSerializable) value).toJSON();
					
					if(json instanceof SSDCollection) parent.add((SSDCollection) json); else
					if(json instanceof SSDObject)     parent.add((SSDObject) json);
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
			
			private static final SSDCollection constructMap(Map<?, ?> map) {
				SSDCollection ssdMap = SSDCollection.empty();
				
				for(Entry<?, ?> entry : map.entrySet()) {
					setObjectParam(ssdMap, String.valueOf(entry.getKey()), entry.getValue());
				}
				
				return ssdMap;
			}
			
			private static final SSDCollection constructArray(List<?> list) {
				SSDCollection ssdArray = SSDCollection.emptyArray();
				
				for(Object item : list) {
					addObjectParam(ssdArray, item);
				}
				
				return ssdArray;
			}
			
			private static final void addObjectParam(SSDCollection parent, Object value) {
				if(value instanceof Map) {
					parent.add(constructMap((Map<?, ?>) value));
				} else if(value instanceof List) {
					parent.add(constructArray((List<?>) value));
				} else {
					addPrimitiveParam(parent, value);
				}
			}
			
			private static final void setObjectParam(SSDCollection parent, String name, Object value) {
				if(value instanceof Map) {
					parent.setDirect(name, constructMap((Map<?, ?>) value));
				} else if(value instanceof List) {
					parent.setDirect(name, constructArray((List<?>) value));
				} else {
					setPrimitiveParam(parent, name, value);
				}
			}
			
			private static final SSDCollection paramsOf(Map<Object, Object> params) {
				SSDCollection json = SSDCollection.empty();
				
				for(Entry<Object, Object> entry : params.entrySet()) {
					setObjectParam(json, String.valueOf(entry.getKey()), entry.getValue());
				}
				
				return json;
			}
			
			public static final SSDCollection bodyOf(String method, Map<Object, Object> params) {
				SSDCollection json = SSDCollection.empty();
				setHeader(json);
				json.set("method", method);
				json.set("params", paramsOf(params));
				return json;
			}
		}
	}
}