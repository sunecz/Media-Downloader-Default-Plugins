package sune.app.mediadown.media_engine.iprima;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import sune.app.mediadown.media_engine.iprima.IPrimaHelper._Singleton;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.MessageException;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.Nuxt;
import sune.app.mediadown.media_engine.iprima.PrimaCommon.RPC;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
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
	
	private static final class MDI {
		
		private static final String URL_API = "https://s0.api.mdi.sune.app";
		
		private MDI() {
		}
		
		public static final void getPrograms(ListTask<Program> task) throws Exception {
			String uriTemplate = URL_API + "/websites/iprima/programs?cursor=%{cursor}s&size=100";
			String cursor = Net.encodeURL(Utils.base64URLEncode("{}"));
			
			do {
				URI reqUri = Net.uri(Utils.format(uriTemplate, "cursor", cursor));
				
				try(Response.OfStream response = Web.requestStream(Request.of(reqUri).GET())) {
					if(response.statusCode() != 200) {
						return; // Fast-fail
					}
					
					JSONCollection data = JSON.read(response.stream());
					JSONCollection programs = data.getCollection("programs");
					
					for(JSONCollection item : programs.collectionsIterable()) {
						URI uri = Net.uri(item.getString("uri"));
						String title = item.getString("title");
						String type = item.getString("type");
						Program program = new Program(uri, title, "type", type);
						
						if(!task.add(program)) {
							return; // Do not continue
						}
					}
					
					cursor = data.getString("pagination.next_cursor");
				}
			} while(cursor != null);
		}
	}
	
	private static final class API {
		
		private static final String URL_API_PLAY = "https://api.play-backend.iprima.cz/api/v1/products/play/ids-%{play_id}s";
		
		private API() {
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
		
		private static final PrimaAuthenticator.SessionData logIn() throws Exception {
			return PrimaAuthenticator.sessionData();
		}
		
		private static final HttpHeaders logInHeaders() throws Exception {
			return PrimaAuthenticator.sessionHeaders();
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
			return ListTask.of(PrimaCommon.handleErrors((task) -> MDI.getPrograms(task)));
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
				String programId = null;
				int retry = 0;
				
				do {
					// If retrying, refresh the current session
					if(retry > 0) {
						PrimaAuthenticator.clearSession();
						requestHeaders = logInHeaders(); // Login again (refresh)
					}
					
					Nuxt nuxt = Nuxt.extract(
						Web.request(Request.of(program.uri()).headers(requestHeaders).GET()).body()
					);
					
					if(nuxt == null) {
						throw new IllegalStateException(
							"Unable to extract information about episodes"
						);
					}
					
					JSONCollection data = Utils.stream(nuxt.data().collectionsIterable())
						.map((c) -> c.getCollection("title"))
						.filter(Objects::nonNull)
						.findFirst().orElse(null);
					
					if(data == null) {
						throw new IllegalStateException(
							"Unable to extract information about episodes"
						);
					}
					
					programId = data.getString("id", null);
				} while(programId == null && ++retry <= 1); // Retry at most once
				
				// Fail early, but only after attempting to retry
				if(programId == null) {
					throw new IllegalStateException(
						"Unable to extract information about episodes"
					);
				}
				
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
				HttpHeaders requestHeaders = logInHeaders(); // Always login first
				JSONCollection nuxtData = null, configData = null, errorInfo = null;
				int retry = 0;
				
				do {
					// If retrying, refresh the current session
					if(retry > 0) {
						PrimaAuthenticator.clearSession();
						requestHeaders = logInHeaders(); // Login again (refresh)
					}
					
					Nuxt nuxt = Nuxt.extract(
						Web.request(Request.of(uri).headers(requestHeaders).GET()).body()
					);
					
					if(nuxt == null) {
						throw new IllegalStateException(
							"Unable to extract information about media content"
						);
					}
					
					nuxtData = Utils.stream(nuxt.data().collectionsIterable())
						.map((c) -> c.getCollection("content"))
						.filter(Objects::nonNull)
						.findFirst().orElse(null);
					
					if(nuxtData == null) {
						throw new IllegalStateException(
							"Unable to extract information about media content"
						);
					}
					
					String videoPlayId = nuxtData.getString("additionals.videoPlayId", null);
					
					if(videoPlayId == null) {
						throw new IllegalStateException("Unable to extract video play ID");
					}
					
					URI configUri = Net.uri(Utils.format(URL_API_PLAY, "play_id", videoPlayId));
					
					try(Response.OfStream configResponse = Web.requestStream(
							Request.of(configUri).headers(requestHeaders).GET()
					)) {
						configData = JSON.read(configResponse.stream());
					}
					
					// Look for any errors in the output
					errorInfo = Utils.stream(configData.collectionsIterable())
						.map((c) -> c.getCollection("errorResult"))
						.filter(Objects::nonNull)
						.findFirst().orElse(null);
				} while(errorInfo != null && ++retry <= 1); // Retry at most once
				
				// First, check whether there is any error (after retrying)
				if(errorInfo != null) {
					displayError(errorInfo);
					return; // Do not continue
				}
				
				// Get information for the media title
				String programName = nuxtData.getString("additionals.programTitle", "");
				String numSeason = nuxtData.getString("additionals.seasonNumber", null);
				String numEpisode = nuxtData.getString("additionals.episodeNumber", null);
				String episodeName = nuxtData.getString("title", "");
				
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