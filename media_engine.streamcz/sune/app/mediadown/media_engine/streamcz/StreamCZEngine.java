package sune.app.mediadown.media_engine.streamcz;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.CookieManager;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.Episode;
import sune.app.mediadown.Program;
import sune.app.mediadown.Shared;
import sune.app.mediadown.download.segment.FileSegmentsHolder;
import sune.app.mediadown.engine.MediaEngine;
import sune.app.mediadown.media.AudioMedia;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;
import sune.app.mediadown.media.MediaContainer;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.media.MediaResolution;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.MediaUtils.Parser.FormatParserData;
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.media.VideoMedia;
import sune.app.mediadown.media.VideoMediaContainer;
import sune.app.mediadown.media_engine.streamcz.M3U_Hotfix.M3UFile;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.util.CheckedBiFunction;
import sune.app.mediadown.util.CheckedSupplier;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Reflection;
import sune.app.mediadown.util.Reflection2;
import sune.app.mediadown.util.Reflection3;
import sune.app.mediadown.util.Threads;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Web;
import sune.app.mediadown.util.Web.GetRequest;
import sune.app.mediadown.util.Web.StreamResponse;
import sune.app.mediadown.util.WorkerProxy;
import sune.app.mediadown.util.WorkerUpdatableTask;
import sune.util.ssdf2.SSDCollection;
import sune.util.ssdf2.SSDObject;

public final class StreamCZEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// RegExp
	private static final Pattern REGEX_SEASON;
	
	static {
		REGEX_SEASON = Pattern.compile("(?i)^(\\d+). série$");
	}
	
	// Allow to create an instance when registering the engine
	StreamCZEngine() {
	}
	
	// ----- Internal methods
	
	private final WorkerProxy _dwp = WorkerProxy.defaultProxy();
	
	private final List<Program> internal_getPrograms() throws Exception {
		return internal_getPrograms(_dwp, (p, a) -> true);
	}
	
	private final List<Program> internal_getPrograms(WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Program, Boolean> function) throws Exception {
		List<Program> programs = new ArrayList<>();
		URI baseUri = Utils.uri("https://www.stream.cz/");
		ConcurrentLoop<API.Node> loop;
		
		(loop = new ConcurrentLoop<>() {
			
			@Override
			protected void iteration(API.Node category) throws Exception {
				for(API.Node item : API.programs(category.id())) {
					URI uri = baseUri.resolve(item.urlName());
					Program program = new Program(uri, item.name(), "id", item.id());
					
					programs.add(program);
					if(!function.apply(proxy, program)) {
						terminate();
						break; // Do not continue
					}
				}
			}
		}).iterate(API.categories());
		
		// Do not return partial results, if terminated
		if(loop.isTerminated()) return null;
		
		return programs;
	}
	
	private final List<Episode> internal_getEpisodes(Program program) throws Exception {
		return internal_getEpisodes(program, _dwp, (p, a) -> true);
	}
	
	private final List<Episode> internal_getEpisodes(Program program, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Episode, Boolean> function) throws Exception {
		List<Episode> episodes = new ArrayList<>();
		String programId = program.get("id");
		
		if(programId == null) {
			Document document = FastWeb.document(program.uri());
			SSDCollection state = API.appServerState(document);
			programId = state.getString("fetchable.tag.show.data.id");
			
			if(programId == null) {
				throw new IllegalStateException("Program ID is null");
			}
		}
		
		for(API.Node item : API.episodes(programId)) {
			URI uri = Utils.uri(Utils.urlConcat(program.uri().toString(), item.urlName()));
			Episode episode = new Episode(program, uri, item.name(), "id", item.id());
			
			episodes.add(episode);
			if(!function.apply(proxy, episode)) {
				return null; // Do not continue
			}
		}
		
		return episodes;
	}
	
	private final List<Media> internal_getMedia(String url) throws Exception {
		return internal_getMedia(url, _dwp, (p, a) -> true);
	}
	
	private final List<Media> internal_getMedia(String url, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
		List<Media> sources = new ArrayList<>();
		URI sourceURI = Utils.uri(url);
		MediaSource source = MediaSource.of(this);
		
		Document document = FastWeb.document(sourceURI);
		SSDCollection state = API.appServerState(document);
		SSDCollection videoData = state.getCollection("fetchable.episode.videoDetail.data");
		String splBaseUrl = videoData.getDirectString("spl");
		String splUrl = String.format("%s%s,%d,%s", splBaseUrl, "spl2", 3, "VOD").replace("|", "%7C");
		URI splUri = Utils.uri(splUrl);
		SSDCollection data = JSON.read(FastWeb.getRequest(splUri, Map.of()).body());
		
		String programName = videoData.getDirectString("name", null);
		String numSeason = null;
		String numEpisode = null;
		String episodeName = "";
		
		// The video should always have a name, but if not, use the document's title
		if(programName == null) {
			programName = document.title();
			
			// Remove unnecessary text from the title, such as the website's title
			int index;
			if((index = programName.indexOf('|')) >= 0) {
				programName = programName.substring(0, index);
			}
		}
		
		// Also check for the video's origin (i.e. the program for episodes)
		String originId = videoData.getString("originTag.id", null);
		String originName = videoData.getString("originTag.name", null);
		if(originName != null && !originName.equalsIgnoreCase(programName)) {
			episodeName = programName;
			programName = originName;
		}
		
		String seasonUrlName = null;
		SSDCollection tags = videoData.getDirectCollection("allParentTags", null);
		if(tags != null) {
			for(SSDCollection tag : tags.collectionsIterable()) {
				String category = tag.getDirectString("category");
				String name = tag.getDirectString("name");
				
				if(category.equalsIgnoreCase("season")) {
					Matcher matcher = REGEX_SEASON.matcher(name);
					seasonUrlName = tag.getDirectString("urlName");
					numSeason = matcher.matches() ? matcher.group(1) : name;
				}
			}
		}
		
		// If the current media is not a movie, i.e. has more than one episode
		if(!episodeName.isEmpty()) {
			// Obtain the episode number manually from the list of episodes
			String episodeId = videoData.getDirectString("id");
			List<API.Node> episodes = null;
			
			// Check whether a season was found. A season is actually just a tab on the program's page.
			if(seasonUrlName != null) {
				// Fix the season, if necessary
				if(numSeason == null || numSeason.isEmpty()) {
					numSeason = "1";
				}
				
				episodes = API.seasonEpisodes(seasonUrlName);
			} else {
				numSeason = null; // No season found, unset it
				
				if(originId != null) {
					episodes = API.episodes(originId);
				}
			}
			
			if(episodes != null) {
				// Episodes are already in the reverse order (the latest is first),
				// so we can just iterate over them normally.
				for(int i = 0, l = episodes.size(); i < l; ++i) {
					API.Node episode = episodes.get(i);
					
					if(episode.id().equals(episodeId)) {
						numEpisode = Integer.toString(l - i);
						break;
					}
				}
			}
		}
		
		// Non-integer non-empty season (such as Bonuses, Videos, etc.) is better appended to the program's name
		if(numSeason != null && !numSeason.isEmpty() && !Utils.isInteger(numSeason)) {
			// Ignore the season (tab/category) of all episodes
			if(!numSeason.equalsIgnoreCase("epizody")) {
				programName += " - " + Utils.titlize(numSeason);
			}
			
			numSeason = null; // Unset the season
		}
		
		boolean splitSeasonAndEpisode = !Utils.isInteger(numEpisode);
		String title = MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName, splitSeasonAndEpisode);
		
		// Check, if the media has some additional subtitles
		List<SubtitlesMedia.Builder<?, ?>> subtitlesMedia = new ArrayList<>();
		SSDCollection subtitlesArray;
		if((subtitlesArray = data.getCollection("data.subtitles", null)) != null) {
			// Parse the subtitles and add them to all obtained media
			for(SSDCollection subtitlesItem : subtitlesArray.collectionsIterable()) {
				MediaLanguage subtitleLanguage = MediaLanguage.ofCode(subtitlesItem.getDirectString("language"));
				
				loop:
				for(SSDObject subtitleUrlObj : subtitlesItem.getDirectCollection("urls").objectsIterable()) {
					String subtitleUrl = subtitleUrlObj.stringValue();
					URI uri = Utils.isRelativeURL(subtitleUrl) ? splUri.resolve(subtitleUrl) : Utils.uri(subtitleUrl);
					MediaFormat subtitleFormat = MediaFormat.UNKNOWN;
					
					switch(subtitleUrlObj.getName()) {
						case "srt":    subtitleFormat = MediaFormat.SRT; break;
						case "webvtt": subtitleFormat = MediaFormat.VTT; break;
						default: continue loop; // Skip the subtitles
					}
					
					SubtitlesMedia.Builder<?, ?> subtitles = SubtitlesMedia.simple().source(source)
						.uri(uri).format(subtitleFormat).language(subtitleLanguage);
					
					subtitlesMedia.add(subtitles);
				}
			}
		}
		
		SSDCollection filesMP4 = data.getCollection("data.mp4", null);
		if(filesMP4 != null) {
			for(SSDCollection item : filesMP4.collectionsIterable()) {
				String strQuality = item.getName();
				MediaQuality quality = MediaQuality.fromString(strQuality, MediaType.VIDEO);
				int bandwidth = item.getDirectInt("bandwidth", -1);
				String codec = item.getDirectString("codec", null);
				double duration = item.getDirectDouble("duration", MediaConstants.UNKNOWN_DURATION * 1000.0) / 1000.0;
				MediaResolution resolution = Opt.of(item.getDirectCollection("resolution", null))
				   .ifTrue(Objects::nonNull).map((v) -> new MediaResolution(v.getInt(0), v.getInt(1)))
				   .orElse(MediaResolution.UNKNOWN);
				MediaFormat format = MediaFormat.MP4;
				String strUrl = item.getDirectString("url").replace("|", "%7C");
				URI uri = Utils.isRelativeURL(strUrl) ? splUri.resolve(strUrl) : Utils.uri(strUrl);
				MediaMetadata metadata = MediaMetadata.builder().sourceURI(sourceURI).title(title).build();
				List<String> codecs = List.of(codec);
				
				Media.Builder<?, ?> media = VideoMedia.simple().source(source)
					.uri(uri).format(format).quality(quality).resolution(resolution)
					.bandwidth(bandwidth).codecs(codecs).duration(duration)
					.metadata(metadata);
				
				// Add additional subtitles, if any
				if(!subtitlesMedia.isEmpty()) {
					Media.Builder<?, ?> audio = AudioMedia.simple().source(source)
						.uri(uri).format(MediaFormat.M4A)
						.quality(MediaQuality.UNKNOWN)
						.language(MediaLanguage.UNKNOWN).duration(duration)
						.metadata(metadata);
					
					MediaContainer.Builder<?, ?> container = VideoMediaContainer.combined()
						.source(source).uri(uri).format(format).quality(quality).resolution(resolution)
						.bandwidth(bandwidth).codecs(codecs).duration(duration).metadata(metadata)
						.media(media, audio);
					
					MediaContainer.Builder<?, ?> wrapper = VideoMediaContainer.separated()
						.source(source).uri(uri).format(format).quality(quality).resolution(resolution)
						.bandwidth(bandwidth).codecs(codecs).duration(duration).metadata(metadata)
						.media(Stream.concat(Stream.of(container), subtitlesMedia.stream()).collect(Collectors.toList()));
					
					media = wrapper;
				}
				
				Media built = media.build();
				sources.add(built);
				if(!function.apply(proxy, built))
					return null; // Do not continue
			}
		}
		
		for(String collectionName : List.of("pls.hls")) {
			SSDCollection file = data.getCollection(collectionName, null);
			
			// If the collection does not exist, just skip it
			if(file == null) continue;
			
			String strUrl = file.getDirectString("url").replace("|", "%7C");
			URI uri = Utils.isRelativeURL(strUrl) ? splUri.resolve(strUrl) : Utils.uri(strUrl);
			MediaLanguage language = MediaLanguage.UNKNOWN;
			MediaMetadata metadata = MediaMetadata.empty();
			List<Media.Builder<?, ?>> media = Hotfix.createMediaBuilders(source, uri, sourceURI, title, language, metadata);
			
			// Add additional subtitles, if any
			if(!subtitlesMedia.isEmpty()) {
				List<Media.Builder<?, ?>> wrappedMedia = new ArrayList<>(media.size());
				
				for(Media.Builder<?, ?> m : media) {
					VideoMedia.Builder<?, ?> video = Utils.cast(((MediaContainer.Builder<?, ?>) m).media().stream()
						.filter((a) -> a.type().is(MediaType.VIDEO)).findFirst().get());
					
					MediaContainer.Builder<?, ?> wrapper = VideoMediaContainer.separated().source(video.source())
						.uri(video.uri()).size(video.size()).quality(video.quality()).metadata(video.metadata())
						.resolution(video.resolution()).duration(video.duration()).codecs(video.codecs())
						.bandwidth(video.bandwidth()).frameRate(video.frameRate())
						.media(Stream.concat(Stream.of(m), subtitlesMedia.stream()).collect(Collectors.toList()));
					
					wrappedMedia.add(wrapper);
				}
				
				media = wrappedMedia;
			}
			
			// Finally, add all the media
			for(Media s : Utils.iterable(media.stream().map(Media.Builder::build).iterator())) {
				sources.add(s);
				if(!function.apply(proxy, s))
					return null; // Do not continue
			}
		}
		
		return sources;
	}
	
	private final List<Media> internal_getMedia(Episode episode) throws Exception {
		return internal_getMedia(episode, _dwp, (p, a) -> true);
	}
	
	private final List<Media> internal_getMedia(Episode episode, WorkerProxy proxy,
			CheckedBiFunction<WorkerProxy, Media, Boolean> function) throws Exception {
		return internal_getMedia(episode.uri().toString(), proxy, function);
	}
	
	// -----
	
	@Override
	public List<Program> getPrograms() throws Exception {
		return internal_getPrograms();
	}
	
	@Override
	public List<Episode> getEpisodes(Program program) throws Exception {
		return internal_getEpisodes(program);
	}
	
	@Override
	public List<Media> getMedia(Episode episode) throws Exception {
		return internal_getMedia(episode);
	}
	
	@Override
	public WorkerUpdatableTask<CheckedBiFunction<WorkerProxy, Program, Boolean>, Void> getPrograms
			(CheckedBiFunction<WorkerProxy, Program, Boolean> function) {
		return WorkerUpdatableTask.voidTaskChecked(function, (p, f) -> internal_getPrograms(p, f));
	}
	
	@Override
	public WorkerUpdatableTask<CheckedBiFunction<WorkerProxy, Episode, Boolean>, Void> getEpisodes
			(Program program,
			 CheckedBiFunction<WorkerProxy, Episode, Boolean> function) {
		return WorkerUpdatableTask.voidTaskChecked(function, (p, f) -> internal_getEpisodes(program, p, f));
	}
	
	@Override
	public WorkerUpdatableTask<CheckedBiFunction<WorkerProxy, Media, Boolean>, Void> getMedia
			(Episode episode,
			 CheckedBiFunction<WorkerProxy, Media, Boolean> function) {
		return WorkerUpdatableTask.voidTaskChecked(function, (p, c) -> internal_getMedia(episode, p, c));
	}
	
	@Override
	public List<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return internal_getMedia(uri.toString());
	}
	
	@Override
	public boolean isDirectMediaSupported() {
		return true;
	}
	
	@Override
	public boolean isCompatibleURL(String url) {
		URL urlObj = Utils.url(url);
		// Check the protocol
		String protocol = urlObj.getProtocol();
		if(!protocol.equals("http") &&
		   !protocol.equals("https"))
			return false;
		// Check the host
		String host = urlObj.getHost();
		if((host.startsWith("www."))) // www prefix
			host = host.substring(4);
		if(!host.equals("stream.cz"))
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
		
		private static final URI URL_CATEGORIES = Utils.uri("https://www.stream.cz/videa/filmy");
		private static final URI URL_API = Utils.uri("https://api.stream.cz/graphql");
		private static final String REFERER = "https://www.stream.cz/";
		
		public static final SSDCollection request(String json) throws Exception {
			return JSON.read(FastWeb.postRequest(URL_API, Map.of("Referer", REFERER, "Content-Type", "application/json"), json).body());
		}
		
		public static final SSDCollection appServerState(Document document) {
			for(Element script : document.select("script:not([src])")) {
				String content = script.html();
				int index;
				
				if((index = content.indexOf("APP_SERVER_STATE = ")) >= 0
						&& (index = content.indexOf("data : ", index)) >= 0) {
					String dataString = Utils.bracketSubstring(content, '{', '}', false, index + 7, content.length());
					return JSON.read(dataString);
				}
			}
			
			return null;
		}
		
		public static final List<Node> categories() throws Exception {
			Document document = FastWeb.document(URL_CATEGORIES);
			SSDCollection state = appServerState(document);
			
			if(state != null) {
				return Stream.concat(
					Utils.stream(state.getCollection("page.navigationCategories.data").collectionsIterable()),
					Stream.of(state.getCollection("fetchable.tag.channel.data"))
				).map(API.Node::fromDirect).collect(Collectors.toList());
			}
			
			return List.of();
		}
		
		private static final List<Node> loop(String infoPath, Function<String, String> queryFunction) throws Exception {
			List<Node> items = new ArrayList<>();
			String cursor = "";
			boolean hasNextPage = false;
			
			do {
				SSDCollection data = request(queryFunction.apply(cursor));
				SSDCollection info = data.getCollection(infoPath);
				SSDCollection pageInfo = info.getDirectCollection("pageInfo");
				cursor = pageInfo.getString("endCursor", "");
				hasNextPage = pageInfo.getBoolean("hasNextPage", false);
				
				Opt.of(info.getDirectCollection("edges", null))
				   .ifTrue(Objects::nonNull)
				   .map((c) -> Utils.stream(c.collectionsIterable()))
				   .map((s) -> s.map(Node::from))
				   .orElseGet(Stream::of)
				   .forEachOrdered(items::add);
			} while(hasNextPage);
			
			return items;
		}
		
		public static final List<Node> programs(String categoryId) throws Exception {
			return loop("data.childTagsData.directTagsConnection", (cursor) -> Query.PROGRAMS.withArgs(categoryId, cursor));
		}
		
		public static final List<Node> episodes(String programId) throws Exception {
			return loop("data.tagData.allEpisodesConnection", (cursor) -> Query.EPISODES.withArgs(programId, cursor));
		}
		
		public static final List<Node> seasonEpisodes(String seasonUrlName) throws Exception {
			return loop("data.tagData.allEpisodesConnection", (cursor) -> Query.SEASON_EPISODES.withArgs(seasonUrlName, cursor));
		}
		
		private static final class Query {
			
			public static final Query PROGRAMS = of(""
				+ "{\"query\":\"\n"
				+ "	query LoadTag($id: ID, $direct_tags_connection_first: Int, $direct_tags_connection_after: String) {\n"
				+ "		childTagsData:tag(id: $id) {\n"
				+ "			directTagsConnection(first: $direct_tags_connection_first, after: $direct_tags_connection_after,"
				+ "categories: [show, tag], mediaTypes: [video]) {\n"
				+ "				...TagCardsFragmentOnTagConnection\n"
				+ "			}\n"
				+ "		}\n"
				+ "	}\n"
				+ "\n"
				+ "	fragment TagCardsFragmentOnTagConnection on TagConnection {\n"
				+ "		totalCount\n"
				+ "		pageInfo {\n"
				+ "			endCursor\n"
				+ "			hasNextPage\n"
				+ "		}\n"
				+ "		edges {\n"
				+ "			node {\n"
				+ "				...TagCardFragmentOnTag\n"
				+ "			}\n"
				+ "		}\n"
				+ "	}\n"
				+ "	\n"
				+ "	fragment TagCardFragmentOnTag on Tag {\n"
				+ "		id\n"
				+ "		name\n"
				+ "		urlName\n"
				+ "	}\n"
				+ "\",\"variables\":{\"id\":\"%s\",\"direct_tags_connection_first\":12,"
				+ "\"direct_tags_connection_after\":\"%s\"}}");
			
			public static final Query EPISODES = of(""
				+ "{\"query\":\"\n"
				+ "	query LoadTag($id: ID, $episodes_connection_first: Int, $episodes_connection_after: String) {\n"
				+ "		tagData:tag(id: $id) {\n"
				+ "			allEpisodesConnection(mediaTypes: [video], first: $episodes_connection_first,"
				+ "after: $episodes_connection_after) {\n"
				+ "				...SeasonEpisodeCardsFragmentOnEpisodeItemConnection\n"
				+ "			}\n"
				+ "		}\n"
				+ "	}\n"
				+ "\n"
				+ "	fragment SeasonEpisodeCardsFragmentOnEpisodeItemConnection on EpisodeItemConnection {\n"
				+ "		totalCount\n"
				+ "		pageInfo {\n"
				+ "			endCursor\n"
				+ "			hasNextPage\n"
				+ "		}\n"
				+ "		edges {\n"
				+ "			node {\n"
				+ "				...SeasonEpisodeCardFragmentOnEpisode\n"
				+ "			}\n"
				+ "		}\n"
				+ "	}\n"
				+ "\n"
				+ "	fragment SeasonEpisodeCardFragmentOnEpisode on Episode {\n"
				+ "		id\n"
				+ "		name\n"
				+ "		urlName\n"
				+ "	}\n"
				+ "\",\"variables\":{\"id\":\"%s\",\"episodes_connection_first\":10,"
				+ "\"episodes_connection_after\":\"%s\"}}");
			
			public static final Query SEASON_EPISODES = of(""
				+ "{\"query\":\"\n"
				+ "	query LoadTag($urlName: String, $episodes_connection_first: Int,"
				+ "$episodes_connection_after: String) {\n"
				+ "		tagData:tag(urlName: $urlName, category: season) {\n"
				+ "			...SeasonDetailFragmentOnTag\n"
				+ "			allEpisodesConnection(mediaTypes: [video], first: $episodes_connection_first,"
				+ "after: $episodes_connection_after) {\n"
				+ "				...SeasonEpisodeCardsFragmentOnEpisodeItemConnection\n"
				+ "			}\n"
				+ "		}\n"
				+ "	}\n"
				+ "\n"
				+ "	fragment SeasonDetailFragmentOnTag on Tag {\n"
				+ "		id\n"
				+ "		name\n"
				+ "		urlName\n"
				+ "	}\n"
				+ "	\n"
				+ "	fragment SeasonEpisodeCardsFragmentOnEpisodeItemConnection on EpisodeItemConnection {\n"
				+ "		totalCount\n"
				+ "		pageInfo {\n"
				+ "			endCursor\n"
				+ "			hasNextPage\n"
				+ "		}\n"
				+ "		edges {\n"
				+ "			node {\n"
				+ "				...SeasonEpisodeCardFragmentOnEpisode\n"
				+ "			}\n"
				+ "		}\n"
				+ "	}\n"
				+ "\n"
				+ "	fragment SeasonEpisodeCardFragmentOnEpisode on Episode {\n"
				+ "		id\n"
				+ "		name\n"
				+ "		urlName\n"
				+ "	}\n"
				+ "\",\"variables\":{\"urlName\":\"%s\",\"episodes_connection_first\":20,"
				+ "\"episodes_connection_after\":\"%s\"}}");
			
			private final String content;
			
			private Query(String content) {
				this.content = Objects.requireNonNull(content);
			}
			
			private static final String unformat(String formattedQuery) {
				return formattedQuery.replace("\t", "\\t").replace("\n", "\\n");
			}
			
			private static final Query of(String formattedQuery) {
				return new Query(unformat(formattedQuery));
			}
			
			public String withArgs(Object... args) {
				return String.format(content, args);
			}
		}
		
		protected static final class Node {
			
			private final String id;
			private final String name;
			private final String urlName;
			
			private Node(String id, String name, String urlName) {
				this.id = Objects.requireNonNull(id);
				this.name = Objects.requireNonNull(name);
				this.urlName = Objects.requireNonNull(urlName);
			}
			
			public static final Node from(SSDCollection json) {
				return fromDirect(json.getDirectCollection("node"));
			}
			
			public static final Node fromDirect(SSDCollection json) {
				return new Node(json.getDirectString("id"), json.getDirectString("name"), json.getDirectString("urlName"));
			}
			
			public String id() {
				return id;
			}
			
			public String name() {
				return name;
			}
			
			public String urlName() {
				return urlName;
			}
		}
	}
	
	private static final class Hotfix {
		
		private static final MethodHandle mh_FPD_result;
		private static final MethodHandle mh_FPD_mediaData;
		
		static {
			// Obtain all the needed method handles
			try {
				Method m_FPD_result = FormatParserData.class.getDeclaredMethod("result", Object.class);
				Method m_FPD_mediaData = FormatParserData.class.getDeclaredMethod("mediaData", MediaMetadata.Builder.class);
				Reflection.setAccessible(m_FPD_result, true);
				Reflection.setAccessible(m_FPD_mediaData, true);
				
				MethodHandles.Lookup lookup = MethodHandles.lookup();
				mh_FPD_result = lookup.unreflect(m_FPD_result);
				mh_FPD_mediaData = lookup.unreflect(m_FPD_mediaData);
			} catch(Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
		
		private static final Media.Builder<?, ?> mapperM3U(FormatParserData<M3UFile> parserData, String title,
				MediaLanguage language, MediaSource source) {
			M3UFile result = parserData.result();
			MediaMetadata metadata = parserData.mediaData().add(parserData.data()).title(title).build();
			return VideoMediaContainer.combined().format(MediaFormat.M3U8).media(
				VideoMedia.segmented().source(source)
					.uri(result.uri()).format(MediaFormat.MP4)
					.quality(MediaQuality.fromResolution(result.resolution()))
					.segments(Utils.<List<FileSegmentsHolder<?>>>cast(result.segmentsHolders()))
					.resolution(result.resolution()).duration(result.duration())
					.metadata(metadata),
				AudioMedia.simple().source(source)
					.uri(result.uri()).format(MediaFormat.M4A)
					.quality(MediaQuality.UNKNOWN)
					.language(language).duration(result.duration())
					.metadata(metadata)
			);
		}
		
		private static final <T> FormatParserData<T> setData(FormatParserData<T> parserData, T result,
				MediaMetadata.Builder mediaData) {
			try {
				mh_FPD_result.invoke(parserData, result);
				mh_FPD_mediaData.invoke(parserData, mediaData);
				return parserData;
			} catch(Throwable tw) {
				throw new IllegalStateException(tw);
			}
		}
		
		private static final List<Media.Builder<?, ?>> parse(URI uri, MediaFormat format, GetRequest request,
				URI sourceURI, Map<String, Object> data, long size, String title, MediaLanguage language,
				MediaSource source) throws Exception {
			if(format == MediaFormat.M3U8) {
				FormatParserData<M3UFile> parserData = new FormatParserData<>(uri, format, request, sourceURI, data, size);
				List<Media.Builder<?, ?>> media = new ArrayList<>();
				
				for(M3UFile result : M3U_Hotfix.parse(request)) {
					boolean isProtected = result.getKey().isPresent();
					MediaMetadata.Builder mediaData = MediaMetadata.builder().isProtected(isProtected).sourceURI(sourceURI);
					Media.Builder<?, ?> m = mapperM3U(setData(parserData, result, mediaData), title, language, source);
					if(m != null) media.add(m);
				}
				
				return media;
			}
			
			return List.of();
		}
		
		private static final List<String> contentType(Map<String, List<String>> headers) {
			return headers.get("Content-Type");
		}
		
		public static final List<Media.Builder<?, ?>> createMediaBuilders(MediaSource source, URI uri, URI sourceURI,
				String title, MediaLanguage language, MediaMetadata data) throws Exception {
			GetRequest request = new GetRequest(uri.toURL(), Shared.USER_AGENT);
			try(StreamResponse response = Web.peek(request.toHeadRequest())) {
				MediaFormat format = Opt.of(contentType(response.headers))
					.ifTrue(Objects::nonNull).map(List::stream).orElseGet(Stream::empty)
					.map(MediaFormat::fromMimeType).filter(Objects::nonNull).findFirst()
					.orElse(MediaFormat.UNKNOWN);
				return parse(uri, format, request, sourceURI, data.data(), Web.size(response.headers),
				             title, language, source);
			}
		}
	}
	
	private static abstract class ConcurrentLoop<T> {
		
		protected final ExecutorService executor = Threads.Pools.newWorkStealing();
		protected final AtomicBoolean terminated = new AtomicBoolean();
		
		protected abstract void iteration(T value) throws Exception;
		
		protected final void await() throws Exception {
			executor.shutdown();
			// Do not throw the InterruptedException (i.e. when cancelled the loop, etc.)
			Utils.ignore(() -> executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS));
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
		
		public void iterate(Iterable<T> values) throws Exception {
			for(T value : values)
				submit(value);
			await();
		}
		
		public void terminate() throws Exception {
			if(!terminated.compareAndSet(false, true))
				return;
			
			executor.shutdownNow();
		}
		
		public boolean isTerminated() {
			return terminated.get();
		}
	}
	
	private static final class FastWeb {
		
		private static final ConcurrentVarLazyLoader<CookieManager> cookieManager
			= ConcurrentVarLazyLoader.of(FastWeb::ensureCookieManager);
		private static final ConcurrentVarLazyLoader<HttpClient> httpClient
			= ConcurrentVarLazyLoader.of(FastWeb::buildHttpClient);
		private static final ConcurrentVarLazyLoader<HttpRequest.Builder> httpRequestBuilder
			= ConcurrentVarLazyLoader.of(FastWeb::buildHttpRequestBuilder);
		
		// Forbid anyone to create an instance of this class
		private FastWeb() {
		}
		
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
		
		public static final String bodyString(Map<String, Object> data) {
			StringBuilder sb = new StringBuilder();
			
			boolean first = true;
			for(Entry<String, Object> e : data.entrySet()) {
				if(first) first = false; else sb.append('&');
				sb.append(URLEncoder.encode(e.getKey(), Shared.CHARSET))
				  .append('=')
				  .append(URLEncoder.encode(Objects.toString(e.getValue()), Shared.CHARSET));
			}
			
			return sb.toString();
		}
		
		public static final HttpResponse<String> getRequest(URI uri, Map<String, String> headers) throws Exception {
			HttpRequest request = maybeAddHeaders(httpRequestBuilder.value().copy().GET().uri(uri), headers).build();
			HttpResponse<String> response = httpClient.value().sendAsync(request, BodyHandlers.ofString(Shared.CHARSET)).join();
			return response;
		}
		
		@SuppressWarnings("unused")
		public static final String get(URI uri, Map<String, String> headers) throws Exception {
			return getRequest(uri, headers).body();
		}
		
		@SuppressWarnings("unused")
		public static final HttpResponse<String> postRequest(URI uri, Map<String, String> headers,
				Map<String, Object> data) throws Exception {
			return postRequest(uri, headers, bodyString(data));
		}
		
		public static final Document document(URI uri, Map<String, String> headers) throws Exception {
			HttpResponse<String> response = getRequest(uri, headers);
			return Utils.parseDocument(response.body(), response.uri());
		}
		
		public static final Document document(URI uri) throws Exception {
			return document(uri, Map.of());
		}
		
		public static final HttpResponse<String> postRequest(URI uri, Map<String, String> headers,
				String payload) throws Exception {
			BodyPublisher body = BodyPublishers.ofString(payload, Shared.CHARSET);
			HttpRequest request = maybeAddHeaders(httpRequestBuilder.value().copy().POST(body).uri(uri), headers).build();
			HttpResponse<String> response = httpClient.value().sendAsync(request, BodyHandlers.ofString(Shared.CHARSET)).join();
			return response;
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
	}
}