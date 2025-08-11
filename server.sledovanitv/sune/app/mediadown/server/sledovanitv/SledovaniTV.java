package sune.app.mediadown.server.sledovanitv;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import sune.app.mediadown.concurrent.VarLoader;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaContainer;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.SegmentedMedia;
import sune.app.mediadown.media.fix.MediaFixer;
import sune.app.mediadown.media.format.M3U.M3USegment;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.server.sledovanitv.Common.AuthenticationException;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

public final class SledovaniTV {
	
	private static VarLoader<SledovaniTV> instance = VarLoader.of(SledovaniTV::new);
	
	private static final Regex REGEX_URI = Regex.of(
		"^https?://sledovanitv\\.(cz|sk)/(?:"
			+ String.join("|",
				"home#(?:"
					+ String.join("|",
						"record(?:%3A|:)(?<recordId>\\d+)",
						"event(?:%3A|:)(?<eventId>[^%:]+(?:%3A|:).+)"
					)
					+ ")",
				"vod/play\\?entryId=(?<entryId>\\d+)"
			)
			+ ")$"
	);
	
	private final API api;
	private volatile API.Session session;
	
	private SledovaniTV() {
		this.api = new API();
	}
	
	private final void authenticate(boolean forceReauth) throws Exception {
		String oldSessionId = session != null ? session.sessionId() : "";
		session = Authenticator.authenticate(api, forceReauth);
		
		if(!oldSessionId.equals(session.sessionId())) {
			Authenticator.saveSession(session);
		}
	}
	
	private final boolean isAuthenticated() {
		return session != null;
	}
	
	private final void ensureAuthenticated(boolean forceReauth) throws Exception {
		if(!forceReauth && isAuthenticated()) {
			return; // Nothing to do
		}
		
		authenticate(forceReauth);
	}
	
	private final UriAction getUriAction(MediaSource source, URI uri) {
		Matcher matcher;
		if(!(matcher = REGEX_URI.matcher(uri.toString())).matches()) {
			return null; // Not supported
		}
		
		String recordId;
		if((recordId = matcher.group("recordId")) != null) {
			return (task) -> getRecordMedia(source, task, uri, Net.decodeURL(recordId));
		}
		
		String eventId;
		if((eventId = matcher.group("eventId")) != null) {
			return (task) -> getEventMedia(source, task, uri, Net.decodeURL(eventId));
		}
		
		String entryId;
		if((entryId = matcher.group("entryId")) != null) {
			return (task) -> getEntryMedia(source, task, uri, Net.decodeURL(entryId));
		}
		
		return null; // Not supported
	}
	
	private final String getTitle(JSONCollection eventData) {
		StringBuilder builder = new StringBuilder();
		String channelName = eventData.getString("channelName");
		String title = eventData.getString("title");
		builder.append(String.format("%s - %s", channelName, title));
		
		String startTime = eventData.getString("startTime");
		String endTime = eventData.getString("endTime");
		
		if(startTime != null && endTime != null) {
			builder.append(String.format(" (%s - %s)", startTime, endTime));
		}
		
		return builder.toString();
	}
	
	private final String getEntryTitle(JSONCollection entryData) {
		String title = entryData.getString("name");
		return title; // Return simple name for now
	}
	
	private final Iterable<SegmentedMedia.Builder<?, ?>> segmentedVTTSubtitles(
		List<Media.Builder<?, ?>> builders
	) {
		return Utils.cast(Utils.iterable(
			builders.stream()
				.filter((b) -> b.format().is(MediaFormat.M3U8))
				.flatMap((b) -> ((MediaContainer.Builder<?, ?>) b).media().stream())
				// Currently only segmened VTT subtitles are supported
				.filter((b) -> (
					b instanceof SegmentedMedia.Builder
						&& b.type().is(MediaType.SUBTITLES)
						&& b.format().is(MediaFormat.VTT)
				))
				.map((b) -> (SegmentedMedia.Builder<?, ?>) b)
				.iterator()
		));
	}
	
	private final List<Media.Builder<?, ?>> getMediaBuilders(
		MediaSource source,
		URI sourceUri,
		String title,
		URI uri
	) throws Exception {
		List<Media.Builder<?, ?>> builders = MediaUtils.createMediaBuilders(
			source, uri, sourceUri, title, MediaLanguage.UNKNOWN, MediaMetadata.empty()
		);
		
		// Since timestamps in the decrypted files are not always correct, we must request
		// both the video and audio to be fixed, if present.
		for(Media.Builder<?, ?> builder : builders) {
			if(!builder.format().isAnyOf(MediaFormat.M3U8, MediaFormat.DASH)) {
				continue; // Currently only M3U8 or DASH segments are supported
			}
			
			List<String> steps = new ArrayList<>();
			
			for(Media.Builder<?, ?> mb : ((MediaContainer.Builder<?, ?>) builder).media()) {
				// Only protected media have video and audio separated
				if(!mb.metadata().isProtected()) {
					continue;
				}
				
				steps.add(
					mb.type().is(MediaType.AUDIO)
						? MediaFixer.Steps.STEP_AUDIO_FIX_TIMESTAMPS
						: MediaFixer.Steps.STEP_VIDEO_FIX_TIMESTAMPS
				);
			}
			
			if(!steps.isEmpty()) {
				MediaMetadata fixMetadata = MediaMetadata.of(
					"media.fix.required", true,
					"media.fix.steps", steps
				);
				
				builder.metadata(MediaMetadata.of(builder.metadata(), fixMetadata));
			}
		}
		
		// Re-time VTT subtitles, if they are present. Subtitles are taken directly from
		// the M3U8 file, so that they can be retimed as they are being downloaded. There
		// are also non-segmented VTT subtitles, but we ignore them here.
		for(SegmentedMedia.Builder<?, ?> smb : segmentedVTTSubtitles(builders)) {
			M3USegment firstSegment = (M3USegment) smb.segments().segments().get(0);
			String startTime = SegmentsHelper.timestamp(firstSegment);
			
			MediaMetadata subtitlesMetadata = MediaMetadata.of(
				"subtitles.retime.strategy", "startAtZero",
				"subtitles.retime.startTime", startTime,
				"subtitles.retime.ignoreHours", true
			);
			
			smb.metadata(MediaMetadata.of(smb.metadata(), subtitlesMetadata));
		}
		
		return builders;
	}
	
	private final void getRecordMedia(
		MediaSource source,
		ListTask<Media> task,
		URI sourceUri,
		String recordId
	) throws Exception {
		JSONCollection data = api.recordMediaSource(session, recordId);
		String title = getTitle(data.getCollection("event"));
		URI uri = Net.uri(data.getString("url"));
		List<Media.Builder<?, ?>> builders = getMediaBuilders(source, sourceUri, title, uri);
		
		for(Media.Builder<?, ?> m : builders) {
			if(!task.add(m.build())) {
				return; // Do not continue
			}
		}
	}
	
	private final void getEventMedia(
		MediaSource source,
		ListTask<Media> task,
		URI sourceUri,
		String eventId
	) throws Exception {
		JSONCollection data = api.eventMediaSource(session, eventId);
		String title = getTitle(data.getCollection("event"));
		URI uri = Net.uri(data.getString("url"));
		List<Media.Builder<?, ?>> builders = getMediaBuilders(source, sourceUri, title, uri);
		
		for(Media.Builder<?, ?> m : builders) {
			if(!task.add(m.build())) {
				return; // Do not continue
			}
		}
	}
	
	private final void getEntryMedia(
		MediaSource source,
		ListTask<Media> task,
		URI sourceUri,
		String entryId
	) throws Exception {
		JSONCollection entryData = api.entryData(session, entryId);
		String title = getEntryTitle(entryData.getCollection("entry"));
		JSONCollection events = entryData.getCollection("events");
		
		if(events.isEmpty()) {
			throw new IllegalStateException("No entry events");
		}
		
		String eventId = String.valueOf(events.getLong("0.id", 0L));
		
		if("0".equals(eventId)) {
			throw new IllegalStateException("Invalid event ID");
		}
		
		JSONCollection data = api.entryMediaSource(session, eventId);
		URI uri = Net.uri(data.getString("stream.url"));
		List<Media.Builder<?, ?>> builders = getMediaBuilders(source, sourceUri, title, uri);
		
		for(Media.Builder<?, ?> m : builders) {
			if(!task.add(m.build())) {
				return; // Do not continue
			}
		}
	}
	
	public static final SledovaniTV instance() {
		return instance.value();
	}
	
	public ListTask<Media> getMedia(MediaSource source, URI uri) throws Exception {
		return ListTask.of(Common.handleErrors((task) -> {
			UriAction action = getUriAction(source, uri);
			
			if(action == null) {
				throw new IllegalArgumentException("Unsupported URI");
			}
			
			ensureAuthenticated(false);
			int retry = 0;
			final int maxRetries = 1;
			
			do {
				try {
					action.run(task);
					break; // All done
				} catch(AuthenticationException ex) {
					if(retry == maxRetries) {
						throw ex; // Rethrow
					}
					
					ensureAuthenticated(true);
				}
			} while(++retry <= maxRetries);
		}));
	}
	
	public boolean isCompatibleUri(URI uri) {
		return uri != null && REGEX_URI.matches(uri.toString());
	}
	
	private static interface UriAction {
		
		void run(ListTask<Media> task) throws Exception;
	}
	
	private static final class SegmentsHelper {
		
		private static DateTimeFormatter dateTimeParser;
		
		private SegmentsHelper() {
		}
		
		private static final DateTimeFormatter dateTimeParser() {
			if(dateTimeParser == null) {
				dateTimeParser = new DateTimeFormatterBuilder()
					.append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
					.optionalStart().appendOffset("+HHMM", "+0000").optionalEnd()
					.toFormatter();
			}
			
			return dateTimeParser;
		}
		
		public static final String timestamp(M3USegment segment) {
			ZonedDateTime instant = Instant
				.from(dateTimeParser().parse(segment.dateTime()))
				.atZone(ZoneOffset.UTC);
			return DateTimeFormatter.ISO_LOCAL_TIME.format(instant);
		}
	}
}
