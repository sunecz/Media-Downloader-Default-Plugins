package sune.app.mediadown.media_engine.iprima;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpHeaders;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.CheckedFunction;
import sune.app.mediadown.util.CheckedRunnable;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONType;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Reflection;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.TriFunction;
import sune.app.mediadown.util.Utils;

final class IPrimaHelper {
	
	private static PluginBase PLUGIN;
	
	private static final int CALLBACK_EXIT = -1;
	private static final String FORMAT_EPISODE_TITLE = "%{season}s - %{episode}s";
	private static final Regex REGEX_EPISODE_NAME = Regex.of("^\\d+\\.[^\\-]+-\\s+(.*)$");
	
	private static final String internal_request(URI uri, HttpHeaders headers) throws Exception {
		return Web.request(Request.of(uri).headers(headers).GET()).body();
	}
	
	private static final String internal_request(URI uri) throws Exception {
		return internal_request(uri, Web.Headers.empty());
	}
	
	// Accumulator must be thread-safe, e.g. ConcurrentSkipListSet
	private static final <S, T> void internal_loopOffsetThreaded(IPrima iprima, S source, int offset, int step,
			Map<String, Object> urlArgs, CheckedFunction<Map<String, Object>, String> urlBuilder,
	        CheckedPentaFunction<IPrima, S, String, Map<String, Object>, CheckedFunction<T, Boolean>, Integer> callback,
	        TriFunction<T, Integer, Integer, Wrapper<T>> wrapperCtor, Set<Wrapper<T>> accumulator) throws Exception {
		try(ThreadedSpawnableTaskQueue<Integer, Integer> queue = new ThreadedSpawnableTaskQueue<Integer, Integer>(2) {
			
			private final AtomicInteger lastOffset = new AtomicInteger();
			
			private final boolean threadSafeListAdder(T val, int offset, int index) throws Exception {
				return accumulator.add(wrapperCtor.apply(val, offset, index)) || true;
			}
			
			@Override
			protected Integer runTask(Integer off) throws Exception {
				Map<String, Object> localURLArgs = new LinkedHashMap<>(urlArgs);
				localURLArgs.put("offset", off);
				URL url = Net.url(urlBuilder.apply(localURLArgs));
				String response = internal_request(Net.uri(url));
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
		}) {
			queue.addTask(0);
		}
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
				
				HttpHeaders requestHeaders = Web.Headers.ofSingleMap(mutRequestHeaders);
				String content = internal_request(Net.uri(configURL), requestHeaders);
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
						String regex = "^" + Regex.quote(programName) + " "
						                   + Regex.quote(seasonRoman) + " \\("
								           + Regex.quote(rawEpisode) + "\\)$";
						
						if(Regex.matches(regex, episodeName)) {
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
					List<Media> media = MediaUtils.createMedia(source, Net.uri(src), sourceURI, title,
						language, MediaMetadata.empty());
					
					for(Media s : media) {
						if(!task.add(s)) {
							return; // Do not continue
						}
					}
				}
			});
		}
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
			URL_API_IPRIMA = "https://prima.iprima.cz/iprima-api/View_Entity_Node_Program/ThemedContent/More"
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
		
		private final Episode parseEpisodeElement(Element elEpisode, Program program, String seasonTitle) {
			// Only add free episodes (those with no price tag)
			if(elEpisode.selectFirst(SELECTOR_PRICE_WRAPPER) != null)
				return null;
			
			Element elEpisodeLink = elEpisode.selectFirst(SELECTOR_EPISODE_LINK);
			// Sometimes an episode is not playable due to e.g. licensing, just skip it
			if(elEpisodeLink == null) return null;
			
			String episodeURL = Net.uriFix(elEpisodeLink.attr("href"));
			String episodeTitle = elEpisodeLink.attr("title");
			String episodeSeasonTitle = seasonTitle == null ? getEpisodeSeasonTitle(elEpisode) : seasonTitle;
			
			if(episodeSeasonTitle != null) {
				episodeTitle = Utils.format(FORMAT_EPISODE_TITLE, "season", episodeSeasonTitle, "episode",
				                            elEpisodeLink.attr("title"));
			}
			
			return new Episode(program, Net.uri(episodeURL), episodeTitle);
		}
		
		private final Episode parseEpisodeArticleElement(Element elEpisode, Program program) {
			Element elEpisodeLink = elEpisode.selectFirst(SELECTOR_EPISODE_LINK);
			// Sometimes an episode is not playable due to e.g. licensing, just skip it
			if(elEpisodeLink == null) return null;
			
			String episodeURL = Net.uriFix(elEpisodeLink.attr("href"));
			String episodeTitle = elEpisode.selectFirst("h4").text();
			String episodeSeasonTitle = getEpisodeSeasonTitle(elEpisode);
			
			if(episodeSeasonTitle != null) {
				episodeTitle = Utils.format(FORMAT_EPISODE_TITLE, "season", episodeSeasonTitle, "episode",
				                            elEpisodeLink.attr("title"));
			}
			
			return new Episode(program, Net.uri(episodeURL), episodeTitle);
		}
		
		private final boolean getEpisodesFromDocument(Program program, Document document,
				String seasonTitle, CheckedFunction<Episode, Boolean> adder) throws Exception {
			for(Element elEpisode : document.select(SELECTOR_EPISODE)) {
				Episode episode = parseEpisodeElement(elEpisode, program, seasonTitle);
				
				if(episode != null && !adder.apply(episode)) {
					return false; // Do not continue
				}
			}
			
			return true;
		}
		
		private final int callback(IPrima iprima, Program program, String response, Map<String, Object> args,
				CheckedFunction<Episode, Boolean> adder) throws Exception {
			JSONCollection data = JSON.read(response);
			String content = data.getString("related_content", null);
			
			if(content == null) {
				return CALLBACK_EXIT; // Do not continue
			}
			
			Document document = HTML.parse(content);
			
			if(!getEpisodesFromDocument(program, document, null, adder)) {
				return CALLBACK_EXIT; // Do not continue
			}
			
			// Check if in the next iteration would be any items
			if(data.getBoolean("hide_load_more_button")) {
				return CALLBACK_EXIT;
			}
			
			// If so, return the next offset, provided in the response
			return data.getInt("offset");
		}
		
		public ListTask<Episode> getEpisodes(Program program) throws Exception {
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
						return;
					}
				} else {
					// Since there is currently no known way to get episodes for all seasons
					// or only for a specific season, we have to examine the whole document
					// structure to obtain all the information about whether it can actually
					// be done the traditional way.
					Document document = HTML.from(program.uri());
					// First check if there are any seasons present
					Element elSeasons = document.selectFirst(SELECTOR_SEASONS);
					
					if(elSeasons != null) {
						for(Element elSeason : elSeasons.children()) {
							// It is really only a season if there is number of episodes specified
							// in the description. Apart from seasons, also include recently aired
							// episodes. This will probably result in duplicates but it should not
							// matter.
							if(elSeason.selectFirst(SELECTOR_SEASON_DESCRIPTION).text().trim().isEmpty()
									&& !Net.uriBasename(elSeason.absUrl("href")).toString().contains("nedavno-odvysilane")) {
								continue;
							}
							
							String seasonTitle = elSeason.selectFirst(SELECTOR_SEASON_TITLE).text().trim();
							String seasonURL = Net.uriFix(elSeason.attr("href"));
							Document seasonDocument = HTML.from(Net.uri(seasonURL));
							
							// Extract all the episodes from the season document
							if(!getEpisodesFromDocument(program, seasonDocument, seasonTitle, task::add)) {
								return;
							}
						}
					} else {
						IPrima iprima = program.get("source");
						Set<Wrapper<Episode>> accumulator = new ConcurrentSkipListSet<>();
						
						// If there are no seasons, we can get all the episodes traditionally,
						// i.e. the request loop way.
						internal_loopOffsetThreaded(iprima, program, 0, 16, Utils.toMap("id", id, "type", TYPE_EPISODES),
							this::urlBuilderIPrimaAPI, this::callback, EpisodeWrapper::new, accumulator);
						
						// Add all obtained episodes from the accumulator manually
						for(Wrapper<Episode> ep : accumulator) task.add(ep.value());
						
						// If there are still no episodes present, try to extract them from
						// the document of the program.
						if(accumulator.isEmpty()) {
							for(Element elEpisode : document.select("#episodes-movie-holder > article")) {
								Episode episode = parseEpisodeArticleElement(elEpisode, program);
								
								if(episode != null) {
									task.add(episode);
								}
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

	static final class PrimaAPIProgramObtainer {
		
		private static final String URL_API;
		private static final String TYPE_MOVIE = "Movies";
		private static final String TYPE_SHOW = "Series";
		private static final String GRAPHQL_TYPE_MOVIE = "VideoNode";
		private static final String GRAPHQL_TYPE_SHOW = "ProgramNode";
		
		// Other
		private static PrimaAPIProgramObtainer INSTANCE;
		
		static {
			URL_API = "https://prima.iprima.cz/iprima-api/ListWithFilter/%{type}s/Content"
					+ "?filter=all"
					+ "&channel_restriction=%{subdomain}s";
		}
		
		private PrimaAPIProgramObtainer() {
		}
		
		public static final PrimaAPIProgramObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new PrimaAPIProgramObtainer()) : INSTANCE;
		}
		
		private static final int callback(ListTask<Program> task, IPrima iprima, String response, String type,
				String graphQLType) throws Exception {
			JSONCollection json = JSON.read(response);
			String content = json.getString("content");
			Document document = HTML.parse(content);
			Elements programs = document.select(".component--scope--cinematography > a");
			
			for(Element elProgram : programs) {
				// Ignore non-free programs
				if(elProgram.selectFirst(".component--scope--cinematography--picture--bottom--price") != null) {
					continue;
				}
				
				String url = Net.uriFix(elProgram.attr("href"));
				String title = elProgram.attr("title");
				JSONCollection data = JSON.read(elProgram.attr("data-item-json"));
				String id = String.valueOf(data.getInt("id"));
				Program program = new Program(Net.uri(url), title, "source", iprima, "id", id, "type", graphQLType);
				
				if(!task.add(program)) {
					return CALLBACK_EXIT; // Do not continue
				}
			}
			
			return programs.size();
		}
		
		private static final void getProgramsOfType(ListTask<Program> task, IPrima iprima, String subdomain,
				String type, String graphQLType) throws Exception {
			String apiURL = Utils.format(URL_API, "subdomain", subdomain, "type", type);
			String response = internal_request(Net.uri(apiURL));
			
			if(response != null) {
				callback(task, iprima, response, type, graphQLType);
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
				String response = internal_request(uriPrograms);
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
	
	static final class SnippetEpisodeObtainer {
		
		// General
		private static final String URL_API;
		
		// RegEx
		private static final Regex REGEX_SNIPPETS;
		
		// Selectors
		private static final String SELECTOR_EPISODE = ".w-full h3 > a";
		
		// Other
		private static SnippetEpisodeObtainer INSTANCE;
		
		static {
			URL_API = "%{base_url}s/_snippet/%{type}s/%{count}d/%{offset}d/%{program_id}s";
			REGEX_SNIPPETS = Regex.of("new\\s+InfiniteCarousel\\([^,]+,\\s*'/_snippet/([^/]+)/[^']+/(\\d+)',[^\\)]+\\)", Regex.Flags.DOTALL);
		}
		
		private SnippetEpisodeObtainer() {
		}
		
		public static final SnippetEpisodeObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new SnippetEpisodeObtainer()) : INSTANCE;
		}
		
		public ListTask<Episode> getEpisodes(Program program) throws Exception {
			return ListTask.of((task) -> {
				URI uri = program.uri();
				String response = internal_request(uri);
				
				if(response == null) {
					return; // Do not continue
				}
				
				Document document = HTML.parse(response, uri);
				String baseURL = uri.getScheme() + "://" + uri.getHost();
				
				scriptsLoop:
				for(Element elScript : document.select("script")) {
					String scriptContent = elScript.html();
					Matcher matcher = REGEX_SNIPPETS.matcher(scriptContent);
					
					if(!matcher.find()) {
						continue;
					}
					
					String snippetType = matcher.group(1);
					String programID = matcher.group(2);
					String urlSnippetAPI = Utils.format(URL_API, "base_url", baseURL, "type", snippetType,
						"count", Integer.MAX_VALUE, "offset", 0, "program_id", programID);
					
					response = internal_request(Net.uri(urlSnippetAPI));
					if(response == null) {
						continue;
					}
					
					Document doc = HTML.parse(response);
					for(Element elEpisode : doc.select(SELECTOR_EPISODE)) {
						String url = elEpisode.attr("href");
						String title = elEpisode.text();
						Episode episode = new Episode(program, Net.uri(url), title);
						
						if(!task.add(episode)) {
							break scriptsLoop; // Do not continue
						}
					}
				}
			});
		}
	}
	
	static final class PlayIDsMediaObtainer {
		
		// URLs
		private static final String URL_API_PLAY;
		
		// RegEx
		private static final Regex REGEX_PLAY_IDS;
		private static final Regex REGEX_NUM_EPISODE;
		
		// Selectors
		private static final String SELECTOR_SCRIPT = ".main-container > .content > header > .play-video > script";
		
		// Other
		private static PlayIDsMediaObtainer INSTANCE;
		
		static {
			URL_API_PLAY = "https://api.play-backend.iprima.cz/api/v1/products/play/ids-%{play_id}s";
			REGEX_PLAY_IDS = Regex.of("videos\\s*=\\s*'([^']+)';");
			REGEX_NUM_EPISODE = Regex.of("^.*?/([^/]+?)$");
		}
		
		private PlayIDsMediaObtainer() {
		}
		
		public static final PlayIDsMediaObtainer getInstance() {
			return INSTANCE == null ? (INSTANCE = new PlayIDsMediaObtainer()) : INSTANCE;
		}
		
		public ListTask<Media> getMedia(URI uri, MediaEngine engine) throws Exception {
			return ListTask.of((task) -> {
				List<String> urls = new ArrayList<>();
				String response = internal_request(uri);
				
				if(response != null) {
					Document document = HTML.parse(response);
					Element elScript = document.selectFirst(SELECTOR_SCRIPT);
					
					if(elScript != null) {
						String scriptContent = elScript.html();
						Matcher matcher = REGEX_PLAY_IDS.matcher(scriptContent);
						
						if(matcher.find()) {
							String[] playIds = matcher.group(1).split(",");
							
							for(String playID : playIds) {
								urls.add(Utils.format(URL_API_PLAY, "play_id", playID));
							}
						}
					}
				}
				
				if(urls.isEmpty()) {
					return; // Do not continue
				}
				
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
				
				HttpHeaders requestHeaders = Web.Headers.ofSingleMap(mutRequestHeaders);
				MediaSource source = MediaSource.of(engine);
				
				for(String urlPlay : urls) {
					String content = internal_request(Net.uri(urlPlay), requestHeaders);
					
					if(content == null || content.isEmpty()) {
						continue;
					}
					
					JSONCollection data = JSON.read(content);
					URI sourceURI = Net.uri(urlPlay);
					
					for(JSONCollection videoData : data.collectionsIterable()) {
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
						JSONCollection streamInfos = videoData.getCollection("streamInfos");
						for(JSONCollection streamInfo : streamInfos.collectionsIterable()) {
							String src = streamInfo.getString("url");
							MediaLanguage language = MediaLanguage.ofCode(streamInfo.getString("lang.key"));
							List<Media> media = MediaUtils.createMedia(source, Net.uri(src), sourceURI, title,
								language, MediaMetadata.empty());
							
							for(Media s : media) {
								if(!task.add(s)) {
									return; // Do not continue
								}
							}
						}
					}
				}
			});
		}
	}
	
	static abstract class Wrapper<T> implements Comparable<Wrapper<T>> {
		
		protected final T value;
		
		public Wrapper(T value) {
			this.value = Objects.requireNonNull(value);
		}
		
		public T value() {
			return value;
		}
	}
	
	static final class EpisodeWrapper extends Wrapper<Episode> {
		
		private static final Comparator<Wrapper<Episode>> comparator;
		
		static {
			comparator = Comparator
				.<Wrapper<Episode>, Integer>comparing((e) -> cast(e).offset)
				.thenComparing((e) -> cast(e).index)
				.thenComparing((a, b) -> compareNatural(cast(a).value.title(), cast(b).value.title()));
		}
		
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
						&& value.title().equalsIgnoreCase(other.value.title());
		}
		
		@Override
		public int compareTo(Wrapper<Episode> w) {
			if(Objects.requireNonNull(w) == this) {
				return 0;
			}
			
			return comparator.compare(this, w);
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
			
			executor.shutdownNow();
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
			
			executor.shutdownNow();
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
			
			executor.shutdownNow();
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
	
	@FunctionalInterface
	static interface CheckedPentaFunction<A, B, C, D, E, R> {
		
		R apply(A a, B b, C c, D d, E e) throws Exception;
		default <V> CheckedPentaFunction<A, B, C, D, E, V> andThen(CheckedFunction<? super R, ? extends V> after) {
			Objects.requireNonNull(after);
			return (A a, B b, C c, D d, E e) -> after.apply(apply(a, b, c, d, e));
		}
	}
}