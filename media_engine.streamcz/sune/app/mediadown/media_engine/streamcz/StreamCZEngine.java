package sune.app.mediadown.media_engine.streamcz;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.scene.image.Image;
import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
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
import sune.app.mediadown.media.SubtitlesMedia;
import sune.app.mediadown.media.VideoMedia;
import sune.app.mediadown.media.VideoMediaContainer;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONObject;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;

public final class StreamCZEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Regex
	private static final Regex REGEX_SEASON = Regex.of("(?iu)^(\\d+). série$");
	private static final Regex REGEX_EPISODE = Regex.of("(?i)^S(\\d+):E(\\d+)$");
	
	// Allow to create an instance when registering the engine
	StreamCZEngine() {
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> {
			URI baseUri = Net.uri("https://www.stream.cz/");
			
			(new ConcurrentLoop<API.Node>() {
				
				@Override
				protected void iteration(API.Node category) throws Exception {
					for(API.Node item : API.programs(category.id())) {
						URI uri = baseUri.resolve(item.urlName());
						Program program = new Program(uri, item.name(), "id", item.id());
						
						if(!task.add(program)) {
							terminate();
							break; // Do not continue
						}
					}
				}
			}).iterate(API.categories());
		});
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return ListTask.of((task) -> {
			String programId = program.get("id");
			Matcher matcher;
			
			if(programId == null) {
				Document document = HTML.from(program.uri());
				JSONCollection state = API.appServerState(document);
				programId = state.getString("fetchable.tag.show.data.id");
				
				if(programId == null) {
					throw new IllegalStateException("Program ID is null");
				}
			}
			
			for(API.Node item : API.episodes(programId)) {
				URI uri = Net.uri(Net.uriConcat(program.uri().toString(), item.urlName()));
				int numEpisode = 0;
				int numSeason = 0;
				
				if(item.namePrefix() != null
						&& (matcher = REGEX_EPISODE.matcher(item.namePrefix())).find()) {
					numSeason = Utils.OfString.asInt(matcher.group(1));
					numEpisode = Utils.OfString.asInt(matcher.group(2));
				}
				
				Episode episode = new Episode(
					program, uri, item.name(), numEpisode, numSeason, new Object[] { "id", item.id() }
				);
				
				if(!task.add(episode)) {
					return; // Do not continue
				}
			}
		});
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			URI sourceURI = uri;
			MediaSource source = MediaSource.of(this);
			
			Document document = HTML.from(sourceURI);
			JSONCollection state = API.appServerState(document);
			JSONCollection videoData = state.getCollection("fetchable.episode.videoDetail.data");
			String splBaseUrl = videoData.getString("spl");
			String splUrl = String.format("%s%s,%d,%s", splBaseUrl, "spl2", 3, "VOD").replace("|", "%7C");
			URI splUri = Net.uri(splUrl);
			JSONCollection json = JSON.read(Web.requestStream(Request.of(splUri).GET()).stream());
			
			String programName = videoData.getString("name", null);
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
			JSONCollection tags = videoData.getCollection("allParentTags", null);
			if(tags != null) {
				for(JSONCollection tag : tags.collectionsIterable()) {
					String category = tag.getString("category");
					String name = tag.getString("name");
					
					if(category.equalsIgnoreCase("season")) {
						Matcher matcher = REGEX_SEASON.matcher(name);
						seasonUrlName = tag.getString("urlName");
						numSeason = matcher.matches() ? matcher.group(1) : name;
					}
				}
			}
			
			// If the current media is not a movie, i.e. has more than one episode
			if(!episodeName.isEmpty()) {
				// Obtain the episode number manually from the list of episodes
				String episodeId = videoData.getString("id");
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
			JSONCollection subtitlesArray;
			if((subtitlesArray = json.getCollection("data.subtitles", null)) != null) {
				// Parse the subtitles and add them to all obtained media
				for(JSONCollection subtitlesItem : subtitlesArray.collectionsIterable()) {
					MediaLanguage subtitleLanguage = MediaLanguage.ofCode(subtitlesItem.getString("language"));
					
					loop:
					for(JSONObject subtitleUrlObj : subtitlesItem.getCollection("urls").objectsIterable()) {
						String subtitleUrl = subtitleUrlObj.stringValue();
						URI subtitleUri = Net.isRelativeURI(subtitleUrl) ? splUri.resolve(subtitleUrl) : Net.uri(subtitleUrl);
						MediaFormat subtitleFormat = MediaFormat.UNKNOWN;
						
						switch(subtitleUrlObj.name()) {
							case "srt":    subtitleFormat = MediaFormat.SRT; break;
							case "webvtt": subtitleFormat = MediaFormat.VTT; break;
							default: continue loop; // Skip the subtitles
						}
						
						SubtitlesMedia.Builder<?, ?> subtitles = SubtitlesMedia.simple().source(source)
							.uri(subtitleUri).format(subtitleFormat).language(subtitleLanguage);
						
						subtitlesMedia.add(subtitles);
					}
				}
			}
			
			JSONCollection filesMP4 = json.getCollection("data.mp4", null);
			if(filesMP4 != null) {
				for(JSONCollection item : filesMP4.collectionsIterable()) {
					String strQuality = item.name();
					MediaQuality quality = MediaQuality.fromString(strQuality, MediaType.VIDEO);
					int bandwidth = item.getInt("bandwidth", -1);
					String codec = item.getString("codec", null);
					double duration = item.getDouble("duration", MediaConstants.UNKNOWN_DURATION * 1000.0) / 1000.0;
					MediaResolution resolution = Opt.of(item.getCollection("resolution", null))
					   .ifTrue(Objects::nonNull).map((v) -> new MediaResolution(v.getInt(0), v.getInt(1)))
					   .orElse(MediaResolution.UNKNOWN);
					MediaFormat format = MediaFormat.MP4;
					String strUrl = item.getString("url").replace("|", "%7C");
					URI mediaUri = Net.isRelativeURI(strUrl) ? splUri.resolve(strUrl) : Net.uri(strUrl);
					MediaMetadata metadata = MediaMetadata.builder().sourceURI(sourceURI).title(title).build();
					List<String> codecs = List.of(codec);
					
					Media.Builder<?, ?> media = VideoMedia.simple().source(source)
						.uri(mediaUri).format(format).quality(quality).resolution(resolution)
						.bandwidth(bandwidth).codecs(codecs).duration(duration)
						.metadata(metadata);
					
					// Add additional subtitles, if any
					if(!subtitlesMedia.isEmpty()) {
						Media.Builder<?, ?> audio = AudioMedia.simple().source(source)
							.uri(mediaUri).format(MediaFormat.M4A)
							.quality(MediaQuality.UNKNOWN)
							.language(MediaLanguage.UNKNOWN).duration(duration)
							.metadata(metadata);
						
						MediaContainer.Builder<?, ?> container = VideoMediaContainer.combined()
							.source(source).uri(mediaUri).format(format).quality(quality).resolution(resolution)
							.bandwidth(bandwidth).codecs(codecs).duration(duration).metadata(metadata)
							.media(media, audio);
						
						MediaContainer.Builder<?, ?> wrapper = VideoMediaContainer.separated()
							.source(source).uri(mediaUri).format(format).quality(quality).resolution(resolution)
							.bandwidth(bandwidth).codecs(codecs).duration(duration).metadata(metadata)
							.media(Stream.concat(Stream.of(container), subtitlesMedia.stream()).collect(Collectors.toList()));
						
						media = wrapper;
					}
					
					Media built = media.build();
					if(!task.add(built)) {
						return; // Do not continue
					}
				}
			}
			
			for(String collectionName : List.of("pls.hls")) {
				JSONCollection file = json.getCollection(collectionName, null);
				
				// If the collection does not exist, just skip it
				if(file == null) continue;
				
				String strUrl = file.getString("url").replace("|", "%7C");
				URI mediaUri = Net.isRelativeURI(strUrl) ? splUri.resolve(strUrl) : Net.uri(strUrl);
				MediaLanguage language = MediaLanguage.UNKNOWN;
				MediaMetadata metadata = MediaMetadata.empty();
				List<Media.Builder<?, ?>> media = MediaUtils.createMediaBuilders(source, mediaUri, sourceURI, title, language, metadata);
				
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
					if(!task.add(s)) {
						return; // Do not continue
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
		String host = uri.getHost();
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
		
		private static final URI URL_CATEGORIES = Net.uri("https://www.stream.cz/videa/filmy");
		private static final URI URL_API = Net.uri("https://api.stream.cz/graphql");
		private static final String REFERER = "https://www.stream.cz/";
		
		public static final JSONCollection request(String json) throws Exception {
			return JSON.read(
				Web.requestStream(
					Request.of(URL_API)
						.headers(Web.Headers.ofSingle("Referer", REFERER))
						.POST(json, "application/json")
				).stream()
			);
		}
		
		public static final JSONCollection appServerState(Document document) {
			for(Element script : document.select("script:not([src])")) {
				String content = script.html();
				int index;
				
				if((index = content.indexOf("APP_SERVER_STATE = ")) >= 0
						&& (index = content.indexOf("data : ", index)) >= 0) {
					String dataString = Utils.bracketSubstring(content, '{', '}', false, index + 7, content.length());
					return JavaScript.readObject(dataString);
				}
			}
			
			return null;
		}
		
		public static final List<Node> categories() throws Exception {
			Document document = HTML.from(URL_CATEGORIES);
			JSONCollection state = appServerState(document);
			
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
				JSONCollection data = request(queryFunction.apply(cursor));
				JSONCollection info = data.getCollection(infoPath);
				JSONCollection pageInfo = info.getCollection("pageInfo");
				cursor = pageInfo.getString("endCursor", "");
				hasNextPage = pageInfo.getBoolean("hasNextPage", false);
				
				Opt.of(info.getCollection("edges", null))
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
				+ "		namePrefix\n"
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
			private final String namePrefix;
			private final String urlName;
			
			private Node(String id, String name, String namePrefix, String urlName) {
				this.id = Objects.requireNonNull(id);
				this.name = Objects.requireNonNull(name);
				this.namePrefix = namePrefix; // May be null
				this.urlName = Objects.requireNonNull(urlName);
			}
			
			public static final Node from(JSONCollection json) {
				return fromDirect(json.getCollection("node"));
			}
			
			public static final Node fromDirect(JSONCollection json) {
				return new Node(
					json.getString("id"),
					json.getString("name"),
					json.getString("namePrefix", null), // Nullable
					json.getString("urlName")
				);
			}
			
			public String id() {
				return id;
			}
			
			public String name() {
				return name;
			}
			
			public String namePrefix() {
				return namePrefix;
			}
			
			public String urlName() {
				return urlName;
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
	}
}