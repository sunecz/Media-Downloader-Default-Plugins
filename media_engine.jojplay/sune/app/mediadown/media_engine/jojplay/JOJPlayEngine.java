package sune.app.mediadown.media_engine.jojplay;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import javafx.scene.image.Image;
import sune.app.mediadown.concurrent.StateMutex;
import sune.app.mediadown.concurrent.SyncObject;
import sune.app.mediadown.configuration.Configuration.ConfigurationProperty;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaLanguage;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginConfiguration;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.CheckedFunction;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JSON.JSONNode;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

public final class JOJPlayEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// Allow to create an instance when registering the engine
	JOJPlayEngine() {
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return API.getPrograms();
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return API.getEpisodes(program);
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return API.getMedia(this, uri, data);
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
		if(!host.equals("play.joj.sk"))
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
		
		private static final String APP_KEY = "AIzaSyB02udgMkNLADkLJ_w5YNBMR2VR1WHfusI";
		private static final String TENANT_ID = "XEpbY0V54AE34rFO7dB2-i9m04";
		
		private static final String DATABASE = "projects/tivio-production/databases/(default)";
		private static final String ORGANIZATION_ID = "dEpbY0V54AE34rFO7dB2";
		private static final String GMPID = "1:1006888934987:web:60408b1ce75bfb5f8cb7ce";
		
		private static final String TYPE_SERIES = "series";
		private static final String TYPE_VIDEO = "video";
		
		private static final int MAX_ARRAY_LENGTH_OP_IN = 30;
		private static final Regex REGEX_URI_PLAYER = Regex.of("^/?player/([^/]+)$");
		
		private static final String[] ROWS_TV_SERIES = new String[] {
			"row-U6Q7n-pRrId2BQhuuNVtw", // Sitkomy
			"row-L4Hr3IvVyI9lv53ygkx6R", // Drámy
			"row-9rKncobnFUUva6fG1egWV", // Dokumentárne seriály
			"row-0YPbYcre13g7Gacn4zyE2", // Kriminálne seriály
			"row-pWoCYvBcZKrl5_D3kLyRl", // Zábavné Relácie
			"row-zUtlpx6F0LTbBt4A2aS-x", // Reality show
			"row-v-PrDUqpQP0nrYMp7uie6", // Komediálne
			"row-y0eB7rXMiBLXsrLLiCOHU", // Súťažné relácie
			"row-BrTpsmp3MBlswgEYOzDB1", // Talkshow
			"row-gQ97VWUEkcLrzp2UNFAXW", // Magazíny
			"row-T5nvQgzklL8pPyfz0XkO1", // Rodinné seriály
		};
		
		private static final String[] ROWS_MOVIES = new String[] {
			"row-6YffydJ-Xq028VyDoPSRK", // Akčné filmy
			"row-Xml3FWqA2UZKE-qQ2q4gV", // Komédie
			"row-JOnrTJCz6noxw1l2zeW0a", // Dokumentárne filmy
			"row-P0pGBymVMQ50YNvoAfzFn", // Rodinné filmy
			"row-nuEpDPqyW_wAQSuAVkz-9", // Drámy
			"row-3pNgAi_I7A7w59r0u8aCi", // Československé klasiky
			"row-YoAgr96kD3ieWFF3B6BNz", // Romantické filmy
			"row-KAnhqdlS_S2AabKeB1FKa", // Sci-fi a Fantasy filmy
			"row-Uvj3bVytsMcrMO2VQ0QSe", // Dobrodružné filmy
			"row-ek20x44KNB0kGauAMSMGi", // Thrillery
			"row-DEBG8iWL4HtGL5Cu_sXtX", // Horory
			"row--1OfC2dDVu5-lkT2-6uxs", // Životopisné filmy
			"row-dRSrMKet1CsxtAcJq-Cjx", // Nemecké filmy
			"row-TfOrrlhWbX0QEaNk5ZPhY", // Európske filmy
			"row-y9Jr95kedVi6t2uaBKT49", // Krátkometrážné filmy
		};
		
		private static final String[] ROWS_KIDS = new String[] {
			"row-1hXc4UKWclp_u8zIWiNI0", // Filmové Rozprávky
			"row-FqANF3TC-0llEviah1cVP", // Detské seriály
			"row-SUcjbviX0evzaBWBWuGVY", // JOJKO
			"row-Wk9CJI0YsEzdbKmLAld3k", // Staré klasiky
		};
		
		private static final FirebaseChannel openChannel() throws Exception {
			return FirebaseChannel.open(Authenticator.login());
		}
		
		private static final String extractTagType(String tag) {
			tag = tag.substring(DATABASE.length() + "/documents/".length());
			
			if(tag.startsWith("organizations")) {
				tag = tag.substring("/organizations/".length() + ORGANIZATION_ID.length());
			}
			
			return Utils.beforeFirst(tag, "/");
		}
		
		private static final List<String> filterTags(List<String> tags, String type) {
			return tags.stream().filter((tag) -> extractTagType(tag).equals(type)).collect(Collectors.toList());
		}
		
		private static final void loopTags(ListTask<Program> task, List<String> tags,
				CheckedFunction<List<String>, List<JSONCollection>> action,
				CheckedFunction<JSONCollection, Program> creator)
				throws Exception {
			final int numTagsPerRequest = MAX_ARRAY_LENGTH_OP_IN;
			
			// Optimize the query, i.e. pack as many tags at once as the API allows.
			for(int i = 0, l = tags.size(); i < l; i += numTagsPerRequest) {
				List<String> tagsView = tags.subList(i, Math.min(l, i + numTagsPerRequest));
				
				for(JSONCollection item : action.apply(tagsView)) {
					Program program = creator.apply(item);
					
					if(!task.add(program)) {
						return; // Do not continue
					}
				}
			}
		}
		
		private static final String resolveFieldValue(JSONCollection field) {
			String value;
			if((value = field.getString("stringValue")) != null) { // Simple value
				return value;
			}
			
			JSONCollection map;
			if((map = field.getCollection("mapValue")) != null) { // Translated value
				return map.getString("fields.sk.stringValue");
			}
			
			return null;
		}
		
		@SafeVarargs
		private static final <T> List<T> concat(T[]... arrays) {
			int size = 0;
			for(T[] array : arrays) size += array.length;
			List<T> list = new ArrayList<>(size);
			for(T[] array : arrays) list.addAll(List.of(array));
			return list;
		}
		
		private static final <T> Iterable<T> reversed(List<T> list) {
			return (() -> {
				return new Iterator<>() {
					
					private final ListIterator<T> it = list.listIterator(list.size());
					
					@Override
					public boolean hasNext() {
						return it.hasPrevious();
					}
					
					@Override
					public T next() {
						return it.previous();
					}
				};
			});
		}
		
		private static final String mediaTitle(JSONCollection document) {
			JSONCollection fields = document.getCollection("fields");
			
			String programName = fields.getString("externals.mapValue.fields.tvProfiTitleSk.stringValue");
			int numEpisode = Integer.valueOf(fields.getString("episodeNumber.integerValue", "-1"));
			int numSeason = Integer.valueOf(fields.getString("seasonNumber.integerValue", "-1"));
			String episodeName = resolveFieldValue(fields.getCollection("name"));
			
			if(programName.equals(episodeName)) {
				episodeName = null;
			}
			
			return MediaUtils.mediaTitle(programName, numSeason, numEpisode, episodeName);
		}
		
		public static final ListTask<Program> getPrograms() throws Exception {
			return ListTask.of((task) -> {
				try(FirebaseChannel channel = openChannel()) {
					List<String> rowTags = new ArrayList<>();
					
					for(String rowId : concat(API.ROWS_TV_SERIES, API.ROWS_MOVIES, API.ROWS_KIDS)) {
						rowTags.addAll(channel.rowTags(rowId));
					}
					
					loopTags(task, filterTags(rowTags, "tags"), channel::tagItems, (item) -> {
						String slug = Utils.afterLast(item.getString("name"), "/");
						String title = resolveFieldValue(item.getCollection("fields.name"));
						URI uri = Net.uri("https://play.joj.sk/series/" + slug);
						return new Program(uri, title, "ref", item.getString("name"), "type", TYPE_SERIES);
					});
					
					loopTags(task, filterTags(rowTags, "videos"), channel::videoItems, (item) -> {
						String slug = Utils.afterLast(item.getString("name"), "/");
						String title = resolveFieldValue(item.getCollection("fields.name"));
						URI uri = Net.uri("https://play.joj.sk/videos/" + slug);
						return new Program(uri, title, "ref", item.getString("name"), "type", TYPE_VIDEO);
					});
				}
			});
		}
		
		public static final ListTask<Episode> getEpisodes(Program program) throws Exception {
			return ListTask.of((task) -> {
				try(FirebaseChannel channel = openChannel()) {
					String ref = program.get("ref");
					
					if(program.get("type").equals("video")) { // Single video
						JSONCollection document = channel.document(ref);
						String slug = Utils.afterLast(document.getString("name"), "/");
						String title = program.title();
						URI uri = Net.uri("https://play.joj.sk/player/" + slug);
						Episode episode = new Episode(program, uri, title, "ref", document.getString("name"));
						task.add(episode);
						return; // Do not continue
					}
					
					for(int seasonNumber : reversed(channel.seasons(ref))) {
						for(JSONCollection item : reversed(channel.seasonEpisodes(ref, seasonNumber))) {
							String slug = Utils.afterLast(item.getString("name"), "/");
							String title = resolveFieldValue(item.getCollection("fields.name"));
							URI uri = Net.uri("https://play.joj.sk/player/" + slug);
							title = seasonNumber + ". Sezóna - " + title;
							Episode episode = new Episode(program, uri, title, "ref", item.getString("name"));
							
							if(!task.add(episode)) {
								return; // Do not continue
							}
						}
					}
				}
			});
		}
		
		public static final ListTask<Media> getMedia(MediaEngine engine, URI uri, Map<String, Object> data) throws Exception {
			return ListTask.of((task) -> {
				try(FirebaseChannel channel = openChannel()) {
					Matcher matcher;
					if(!(matcher = REGEX_URI_PLAYER.matcher(uri.getPath())).matches()) {
						throw new IllegalArgumentException("Not a supported player URI: '" + uri + "'");
					}
					
					String slug = matcher.group(1);
					JSONCollection document = channel.documentOfSlug(slug);
					
					if(document == null) {
						// The slug is not probably a urlName, try to use it as a tag
						String ref = DATABASE + "/documents/videos/" + slug;
						document = channel.document(ref);
					}
					
					if(document == null) {
						throw new IllegalStateException("Unable to obtain media information");
					}
					
					URI sourceURI = uri;
					MediaSource source = MediaSource.of(engine);
					String title = mediaTitle(document);
					
					for(JSONCollection item
							: document.getCollection("fields.sources.arrayValue.values").collectionsIterable()) {
						String url = item.getString("mapValue.fields.url.stringValue");
						MediaLanguage language = MediaLanguage.UNKNOWN;
						MediaMetadata metadata = MediaMetadata.empty();
						
						List<Media> media = MediaUtils.createMedia(
							source, Net.uri(url), sourceURI, title, language, metadata
						);
						
						for(Media s : media) {
							if(!task.add(s)) {
								return; // Do not continue
							}
						}
					}
				}
			});
		}
		
		protected static final class Authenticator {
			
			private static final URI URI_LOGIN;
			
			static {
				URI_LOGIN = Net.uri("https://www.googleapis.com/identitytoolkit/v3/relyingparty/verifyPassword?key=" + APP_KEY);
			}
			
			private Authenticator() {
			}
			
			private static final String doLogin(String email, String password) throws Exception {
				JSONCollection json = JSONCollection.empty();
				json.set("tenantId", TENANT_ID);
				json.set("email", email);
				json.set("password", password);
				json.set("returnSecureToken", true);
				String body = json.toString();
				
				try(Response.OfString response = Web.request(Request.of(URI_LOGIN)
				                	.addHeader("Referer", "https://play.joj.sk/")
				                	.POST(body, "application/json"))) {
					JSONCollection data = JSON.read(response.body());
					String idToken = data.getString("idToken");
					return idToken;
				}				
			}
			
			public static String login() throws Exception {
				return doLogin(AuthenticationData.email(), AuthenticationData.password());
			}
			
			private static final class AuthenticationData {
				
				private AuthenticationData() {
				}
				
				private static final PluginConfiguration configuration() {
					return PLUGIN.getContext().getConfiguration();
				}
				
				private static final <T> T value(String propertyName) {
					return Optional.<ConfigurationProperty<T>>ofNullable(configuration().property(propertyName))
								.map(ConfigurationProperty::value).get();
				}
				
				public static final String email() {
					return value("authData_email");
				}
				
				public static final String password() {
					return value("authData_password");
				}
			}
		}
		
		protected static final class FirebaseChannel implements AutoCloseable {
			
			// JOJ Play uses Firebase as a database. The data are stored in Firestore,
			// which are accessed either using the REST API or using a Channel.
			// Since we do not have a valid OAuth2 token to access the REST API,
			// we use the Channel.
			//
			// It works as follows:
			// (1) Having an endpoint URI, we sent a GET request to it and keep it open
			//     for 60 seconds, which is allegedly the default. This connection will
			//     send us the actual data we want.
			// (2) After opening the connection, we can send POST requests to the same
			//     endpoint URI with a specific query which in turn send us
			//         - actual data to the opened GET connection,
			//         - and a response that further describes the data in the GET
			//           connection.
			
			private static final String BASE_URI = "https://firestore.googleapis.com/google.firestore.v1.Firestore/Listen/channel";
			
			// Why 2? See: https://github.com/firebase/firebase-js-sdk/blob/master/packages/firestore/src/core/target_id_generator.ts#L21
			private static final int TARGET_ID_INCREMENT = 2;
			private static final int TARGET_ID_INITIAL = 2;
			private static final int RESPONSE_ID_INITIAL = 1;
			
			private static final String QUERY_ROOT_PARENT = DATABASE + "/documents";
			private static final String QUERY_PARENT = DATABASE + "/documents/organizations/" + ORGANIZATION_ID;
			private static final int VER = 8;
			private static final int CVER = 22;
			
			private final AtomicInteger ofs = new AtomicInteger();
			private final AtomicInteger targetId = new AtomicInteger(TARGET_ID_INITIAL);
			private final AtomicInteger RID = new AtomicInteger();
			private final AtomicInteger AID = new AtomicInteger();
			
			private final Connection connection = new Connection();
			private volatile Session session;
			
			private FirebaseChannel() {
			}
			
			private static final URI uriWithArgs(Object... args) {
				return Net.uri(BASE_URI + "?" + Net.queryString(args));
			}
			
			private static final <T extends FirestoreResponse.OfContent> Iterable<T> filterContent(
					List<FirestoreResponse.OfContent> data,
					FirestoreResponse.OfContent.ContentType contentType
			) {
				return Utils.iterable(
					data.stream().filter((c) -> c.type() == contentType).map(Utils::<T>cast).iterator()
				);
			}
			
			public static final FirebaseChannel open(String idToken) throws Exception {
				FirebaseChannel channel = new FirebaseChannel();
				channel.openSession(idToken);
				channel.openConnection();
				return channel;
			}
			
			private final int nextOfs() {
				return ofs.getAndIncrement();
			}
			
			private final int nextTargetId() {
				return targetId.getAndAdd(TARGET_ID_INCREMENT);
			}
			
			private final int nextRID() {
				return RID.getAndIncrement();
			}
			
			private final int nextAID() {
				return AID.getAndIncrement();
			}
			
			private final String randomZX() {
				return Utils.randomString(12, "abcdefghijklmnopqrstuvqxyz0123456789");
			}
			
			private final int readLength(Reader reader) throws IOException {
				final int radix = 10;
				int len = 0;
				boolean eof = true;
				
				for(int c; (c = reader.read()) != -1;) {
					if(c == '\n') {
						eof = false;
						break;
					}
					
					if(!Character.isDigit(c)) {
						throw new IllegalStateException("Not a digit: '" + ((char) c) + "'");
					}
					
					len = len * radix + Character.digit(c, radix);
				}
				
				return eof ? -1 : len;
			}
			
			private final String parseResponseString(Reader reader) throws IOException {
				int len;
				if((len = readLength(reader)) < 0) {
					return null;
				}
				
				int total = 0;
				StringBuilder builder = Utils.utf16StringBuilder(len);
				char[] buf = new char[8192];
				
				for(int read; total < len && (read = reader.read(buf, 0, Math.min(buf.length, len - total))) != -1;) {
					builder.append(buf, 0, read);
					total += Character.codePointCount(buf, 0, read);
				}
				
				if(total < len) {
					throw new IOException("Not read fully");
				}
				
				return builder.toString();
			}
			
			private final JSONCollection parseResponse(Reader reader) throws IOException {
				String string = parseResponseString(reader);
				
				if(string == null) {
					throw new IllegalStateException("Invalid response content");
				}
				
				return JSON.read(string);
			}
			
			private final JSONCollection parseResponse(InputStream stream) throws IOException {
				// Do not close the reader, since it would close the stream as well
				Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
				return parseResponse(reader);
			}
			
			private final String requestBody(String... requests) {
				Map<String, Object> args = new LinkedHashMap<>();
				args.put("count", requests.length);
				args.put("ofs", nextOfs());
				
				for(int i = 0, l = requests.length; i < l; ++i) {
					args.put("req" + i + "___data__", requests[i]);
				}
				
				return Net.queryString(args);
			}
			
			private final void openSession(String idToken) throws Exception {
				if(session != null) {
					return; // Session already exists
				}
				
				session = authRequest(idToken);
			}
			
			private final Session authRequest(String idToken) throws Exception {
				String argHttpHeaders = ""
						+ "X-Goog-Api-Client:gl-js/ fire/8.10.1"
						+ "Content-Type:text/plain"
						+ "X-Firebase-GMPID:" + GMPID
						+ "Authorization:Bearer " + idToken; 
				
				URI uri = uriWithArgs(
					"database", DATABASE,
					"VER", VER,
					"CVER", CVER,
					"RID", nextRID(),
					"X-HTTP-Session-Id", "gsessionid",
					"$httpHeaders", argHttpHeaders,
					"zx", randomZX(),
					"t", 1
				);
				
				final int requestTargetId = nextTargetId();
				JSONCollection query = addTargetQuery(
					requestTargetId,
					new StructuredQuery.Builder()
						.parent(QUERY_ROOT_PARENT)
						.from(new StructuredQuery.From.Collection("videos"))
						.orderBy(new StructuredQuery.OrderBy.Field("__name__", StructuredQuery.OrderBy.Direction.ASCENDING))
						.limit(1)
						.build()
				);
				
				String body = requestBody(query.toString(true));
				
				try(Response.OfStream response = Web.requestStream(Request.of(uri).POST(body))) {
					JSONCollection data = parseResponse(response.stream()).getCollection(0);
					String sessionId = response.headers().firstValue("x-http-session-id").get();
					String sid = data.getCollection(1).getString(1);
					return new Session(idToken, sessionId, sid);
				}
			}
			
			private final JSONCollection addTargetQuery(int requestTargetId, JSONCollection raw) {
				JSONCollection json = JSONCollection.empty();
				json.set("database", DATABASE);
				JSONCollection addTarget = JSONCollection.empty();
				for(JSONNode node : raw.nodesIterable()) {
					addTarget.set(node.name(), node);
				}
				addTarget.set("targetId", requestTargetId);
				json.set("addTarget", addTarget);
				return json;
			}
			
			private final JSONCollection addTargetQuery(int requestTargetId, StructuredQuery query) {
				JSONCollection json = JSONCollection.empty();
				json.set("database", DATABASE);
				JSONCollection addTarget = JSONCollection.empty();
				addTarget.set("query.structuredQuery", query.data());
				addTarget.set("query.parent", query.parent());
				addTarget.set("targetId", requestTargetId);
				json.set("addTarget", addTarget);
				return json;
			}
			
			private final <T> FirestoreResponse.OfReference addTarget(
					BiFunction<Integer, T, JSONCollection> querySupplier,
					T value
			) throws Exception {
				final int requestTargetId = nextTargetId();
				JSONCollection json = querySupplier.apply(requestTargetId, value);
				
				URI uri = uriWithArgs(
					"database", DATABASE,
					"VER", VER,
					"gsessionid", session.sessionId(),
					"SID", session.sid(),
					"RID", nextRID(),
					"AID", nextAID(),
					"zx", randomZX(),
					"t", 1
				);
				
				String body = requestBody(json.toString(true));
				
				try(Response.OfStream response = Web.requestStream(Request.of(uri).POST(body))) {
					JSONCollection data = parseResponse(response.stream());
					return FirestoreResponse.OfReference.create(requestTargetId, data);
				}
			}
			
			private final FirestoreResponse.OfReference addTarget(JSONCollection raw) throws Exception {
				return addTarget(this::addTargetQuery, raw);
			}
			
			private final FirestoreResponse.OfReference addTarget(StructuredQuery query) throws Exception {
				return addTarget(this::addTargetQuery, query);
			}
			
			private final JSONCollection removeTargetQuery(int targetId) {
				JSONCollection json = JSONCollection.empty();
				json.set("database", DATABASE);
				json.set("removeTarget", targetId);
				return json;
			}
			
			private final FirestoreResponse.OfReference removeTarget(int targetId) throws Exception {
				final int requestTargetId = nextTargetId();
				JSONCollection json = removeTargetQuery(targetId);
				
				URI uri = uriWithArgs(
					"database", DATABASE,
					"VER", VER,
					"gsessionid", session.sessionId(),
					"SID", session.sid(),
					"RID", nextRID(),
					"AID", nextAID(),
					"zx", randomZX(),
					"t", 1
				);
				
				String body = requestBody(json.toString(true));
				
				try(Response.OfStream response = Web.requestStream(Request.of(uri).POST(body))) {
					return FirestoreResponse.OfReference.create(requestTargetId, parseResponse(response.stream()));
				}
			}
			
			private final void openConnection() throws Exception {
				connection.open();
				connection.removeTarget(TARGET_ID_INITIAL);
				// Wait for all responses to be received before doing something else
				connection.responses(TARGET_ID_INITIAL, RESPONSE_ID_INITIAL);
			}
			
			public final List<JSONCollection> items(String parent, String collectionId, List<String> refs)
					throws Exception {
				List<JSONCollection> items = new ArrayList<>();
				
				FirestoreResponse.OfReference response = addTarget(
					new StructuredQuery.Builder()
						.parent(parent)
						.from(new StructuredQuery.From.Collection(collectionId))
						.where(new StructuredQuery.Where.FieldArray(
							"__name__",
							StructuredQuery.Where.Operation.IN,
							refs.stream()
								.map(StructuredQuery.Where.SimpleValue.OfReference::new)
								.collect(Collectors.toList())
						))
						.orderBy(new StructuredQuery.OrderBy.Field("__name__", StructuredQuery.OrderBy.Direction.ASCENDING))
						.build()
				);
				
				connection.removeTarget(response.requestTargetId());
				List<FirestoreResponse.OfContent> data = connection.responses(response);
				connection.throwIfException();
				
				for(FirestoreResponse.OfDocumentChange item
						: FirebaseChannel.<FirestoreResponse.OfDocumentChange>filterContent(
							data,
							FirestoreResponse.OfContent.ContentType.DOCUMENT_CHANGE
						)
				) {
					JSONCollection document = item.document();
					items.add(document);
				}
				
				return items;
			}
			
			public final List<JSONCollection> tagItems(List<String> tagRefs) throws Exception {
				return items(QUERY_PARENT, "tags", tagRefs);
			}
			
			public final List<JSONCollection> videoItems(List<String> videoRefs) throws Exception {
				return items(QUERY_ROOT_PARENT, "videos", videoRefs);
			}
			
			public final List<String> rowTags(String rowId) throws Exception {
				List<String> tags = new ArrayList<>();
				
				FirestoreResponse.OfReference response = addTarget(
					new StructuredQuery.Builder()
						.parent(QUERY_PARENT)
						.from(new StructuredQuery.From.Collection("rows"))
						.where(new StructuredQuery.Where.FieldString(
							"rowId",
							StructuredQuery.Where.Operation.EQUAL,
							rowId
						))
						.orderBy(new StructuredQuery.OrderBy.Field("__name__", StructuredQuery.OrderBy.Direction.ASCENDING))
						.build()
				);
				
				connection.removeTarget(response.requestTargetId());
				List<FirestoreResponse.OfContent> data = connection.responses(response);
				connection.throwIfException();
				
				for(FirestoreResponse.OfDocumentChange item
						: FirebaseChannel.<FirestoreResponse.OfDocumentChange>filterContent(
							data,
							FirestoreResponse.OfContent.ContentType.DOCUMENT_CHANGE
						)
				) {
					JSONCollection document = item.document();
					JSONCollection arrayTags = document.getCollection("fields.customItems.arrayValue.values");
					
					for(JSONCollection tag : arrayTags.collectionsIterable()) {
						String tagRef = tag.getString("mapValue.fields.itemRef.referenceValue");
						tags.add(tagRef);
					}
				}
				
				return tags;
			}
			
			public final JSONCollection document(String ref) throws Exception {
				return documents(List.of(ref)).get(0);
			}
			
			public final List<JSONCollection> documents(List<String> refs) throws Exception {
				List<JSONCollection> documents = new ArrayList<>();
				
				JSONCollection raw = JSONCollection.empty();
				JSONCollection docs = JSONCollection.emptyArray();
				for(String ref : refs) docs.add(ref);
				raw.set("documents.documents", docs);
				
				FirestoreResponse.OfReference response = addTarget(raw);
				
				connection.removeTarget(response.requestTargetId());
				List<FirestoreResponse.OfContent> data = connection.responses(response);
				connection.throwIfException();
				
				for(FirestoreResponse.OfDocumentChange item
						: FirebaseChannel.<FirestoreResponse.OfDocumentChange>filterContent(
							data,
							FirestoreResponse.OfContent.ContentType.DOCUMENT_CHANGE
						)
				) {
					JSONCollection document = item.document();
					documents.add(document);
				}
				
				return documents;
			}
			
			public final List<Integer> seasons(String programRef) throws Exception {
				List<Integer> seasons = new ArrayList<>();
				JSONCollection document = document(programRef);
				
				for(JSONCollection item
						: document.getCollection("fields.metadata.arrayValue.values").collectionsIterable()) {
					String type = item.getString("mapValue.fields.type.stringValue");
					
					if(!type.equals("AVAILABLE_SEASONS")) {
						continue;
					}
					
					for(JSONCollection season
							: item.getCollection("mapValue.fields.value.arrayValue.values").collectionsIterable()) {
						int number = Integer.valueOf(season.getString("mapValue.fields.seasonNumber.integerValue"));
						seasons.add(number);
					}
				}
				
				return seasons;
			}
			
			public final List<JSONCollection> seasonEpisodes(String programRef, int seasonNumber) throws Exception {
				List<JSONCollection> episodes = new ArrayList<>();
				
				FirestoreResponse.OfReference response = addTarget(
					new StructuredQuery.Builder()
						.parent(QUERY_ROOT_PARENT)
						.from(new StructuredQuery.From.Collection("videos"))
						.where(new StructuredQuery.Where.Composite(
							StructuredQuery.Where.LogicalOperation.AND,
							new StructuredQuery.Where.FieldArray(
      							"tags",
      							StructuredQuery.Where.Operation.ARRAY_CONTAINS_ANY,
      							new StructuredQuery.Where.SimpleValue.OfReference(programRef)
      						),
							new StructuredQuery.Where.FieldString(
								"publishedStatus",
								StructuredQuery.Where.Operation.EQUAL,
								"PUBLISHED"
							),
							new StructuredQuery.Where.FieldString(
								"transcodingStatus",
								StructuredQuery.Where.Operation.EQUAL,
								"ENCODING_DONE"
							),
							new StructuredQuery.Where.FieldInteger(
								"seasonNumber",
								StructuredQuery.Where.Operation.EQUAL,
								seasonNumber
							)
						))
						.orderBy(new StructuredQuery.OrderBy.Field("episodeNumber", StructuredQuery.OrderBy.Direction.ASCENDING))
						.orderBy(new StructuredQuery.OrderBy.Field("__name__", StructuredQuery.OrderBy.Direction.ASCENDING))
						.build()
				);
				
				connection.removeTarget(response.requestTargetId());
				List<FirestoreResponse.OfContent> data = connection.responses(response);
				connection.throwIfException();
				
				for(FirestoreResponse.OfDocumentChange item
						: FirebaseChannel.<FirestoreResponse.OfDocumentChange>filterContent(
							data,
							FirestoreResponse.OfContent.ContentType.DOCUMENT_CHANGE
						)
				) {
					JSONCollection document = item.document();
					episodes.add(document);
				}
				
				return episodes;
			}
			
			public final JSONCollection documentOfSlug(String slug) throws Exception {
				FirestoreResponse.OfReference response = addTarget(
					new StructuredQuery.Builder()
						.parent(QUERY_ROOT_PARENT)
						.from(new StructuredQuery.From.Collection("videos"))
						.where(new StructuredQuery.Where.FieldString(
							"urlName.sk",
							StructuredQuery.Where.Operation.ARRAY_CONTAINS,
							slug
						))
						.orderBy(new StructuredQuery.OrderBy.Field("__name__", StructuredQuery.OrderBy.Direction.ASCENDING))
						.limit(2)
						.build()
				);
				
				connection.removeTarget(response.requestTargetId());
				List<FirestoreResponse.OfContent> data = connection.responses(response);
				connection.throwIfException();
				
				for(FirestoreResponse.OfDocumentChange item
						: FirebaseChannel.<FirestoreResponse.OfDocumentChange>filterContent(
							data,
							FirestoreResponse.OfContent.ContentType.DOCUMENT_CHANGE
						)
				) {
					JSONCollection document = item.document();
					return document;
				}
				
				return null;
			}
			
			@SuppressWarnings("unused")
			public final List<String> screenRows(String refScreen) throws Exception {
				List<String> rows = new ArrayList<>();
				
				FirestoreResponse.OfReference response = addTarget(
					new StructuredQuery.Builder()
						.parent(QUERY_PARENT)
						.from(new StructuredQuery.From.Collection("rows"))
						.where(new StructuredQuery.Where.FieldReference(
							"screenRef",
							StructuredQuery.Where.Operation.EQUAL,
							QUERY_PARENT + "/screens/" + refScreen
						))
						.orderBy(new StructuredQuery.OrderBy.Field("order", StructuredQuery.OrderBy.Direction.ASCENDING))
						.orderBy(new StructuredQuery.OrderBy.Field("__name__", StructuredQuery.OrderBy.Direction.ASCENDING))
						.build()
				);
				
				connection.removeTarget(response.requestTargetId());
				List<FirestoreResponse.OfContent> data = connection.responses(response);
				connection.throwIfException();
				
				for(FirestoreResponse.OfDocumentChange item
						: FirebaseChannel.<FirestoreResponse.OfDocumentChange>filterContent(
							data,
							FirestoreResponse.OfContent.ContentType.DOCUMENT_CHANGE
						)
				) {
					JSONCollection document = item.document();
					
					if(!"ROW".equals(document.getString("fields.rowComponent.stringValue"))) {
						continue;
					}
					
					String rowId = document.getString("fields.rowId.stringValue");
					rows.add(rowId);
				}
				
				return rows;
			}
			
			@Override
			public void close() throws Exception {
				connection.close();
			}
			
			private final class Connection {
				
				private final Map<Integer, FirestoreResponse.OfContent> responses = new HashMap<>();
				private final StateMutex mtxSend = new StateMutex();
				private final SyncObject mtxResponse = new SyncObject();
				private final Set<Integer> queueRemoveTargets = new HashSet<>();
				
				private volatile Thread thread;
				private volatile Exception exception;
				private volatile int currentTargetId;
				
				private Connection() {
				}
				
				private final Thread thread() {
					Thread ref = thread;
					
					if(ref == null) {
						synchronized(this) {
							ref = thread;
							
							if(ref == null) {
								ref = new Thread(this::threadBody);
								ref.setName("FirebaseChannel Connection");
								ref.setDaemon(true);
								thread = ref;
							}
						}
					}
					
					return ref;
				}
				
				private final void startThread() {
					Thread thread = thread();
					
					if(thread.isAlive()) {
						return; // Already started
					}
					
					thread.start();
				}
				
				private final void closeThread() throws IOException {
					Thread ref = thread;
					
					if(ref != null) {
						synchronized(this) {
							ref = thread;
							
							if(ref != null) {
								ref.interrupt();
							}
						}
					}
				}
				
				private final void threadBody() {
					while(!Thread.currentThread().isInterrupted()) {
						try {
							mtxSend.awaitAndReset();
							sendRequest();
						} catch(InterruptedException ex) {
							break; // Exit the loop
						} catch(Exception ex) {
							exception = ex;
							break; // Exit the loop
						}
					}
				}
				
				private final void sendRequest() throws Exception {
					responses.clear();
					
					URI uri = uriWithArgs(
						"database", DATABASE,
						"VER", VER,
						"RID", "rpc",
						"SID", session.sid(),
						"CI", 0,
						"AID", nextAID(),
						"TYPE", "xmlhttp",
						"gsessionid", session.sessionId(),
						"zx", randomZX(),
						"t", 1
					);
					
					Response.OfStream response = Web.requestStream(
						Request.of(uri)
							.addHeader("Referer", "https://play.joj.sk/")
							.addHeader("Accept-Encoding", "gzip")
							.timeout(Duration.ofSeconds(60)).GET()
					);
					
					try(DataPuller puller = new DataPuller(new GZIPInputStream(response.stream()))) {
						while(!Thread.currentThread().isInterrupted()) {
							List<FirestoreResponse.OfContent> data = puller.pull();
							
							if(data == null) {
								break; // EOF
							}
							
							for(FirestoreResponse.OfContent item : data) {
								// Change the current target ID based on the response if it is a targetChange response
								if(item instanceof FirestoreResponse.OfTargetChange) {
									FirestoreResponse.OfTargetChange targetChange = (FirestoreResponse.OfTargetChange) item;
									
									switch(targetChange.targetChangeType()) {
										case ADD: currentTargetId = targetChange.targetId(); break;
										case REMOVE: currentTargetId = 0; break;
										default: break; // Do nothing
									}
								}
								
								synchronized(responses) {
									responses.put(item.lastId(), item);
								}
							}
							
							mtxResponse.unlock();
						}
					}
				}
				
				private final void maybeRemoveTarget(int targetId) throws Exception {
					boolean enqueued = false;
					
					synchronized(queueRemoveTargets) {
						enqueued = queueRemoveTargets.remove(targetId);
					}
					
					if(enqueued) {
						FirebaseChannel.this.removeTarget(targetId);
					}
				}
				
				public final void removeTarget(int targetId) throws Exception {
					if(currentTargetId == targetId) {
						FirebaseChannel.this.removeTarget(targetId);
						return;
					}
					
					synchronized(queueRemoveTargets) {
						queueRemoveTargets.add(targetId);
					}
				}
				
				public final List<FirestoreResponse.OfContent> responses(FirestoreResponse.OfReference response)
						throws Exception {
					return responses(response.requestTargetId(), response.lastId());
				}
				
				public final List<FirestoreResponse.OfContent> responses(int targetId, int lastId) throws Exception {
					List<FirestoreResponse.OfContent> list = null;
					FirestoreResponse.OfContent response;
					int id = lastId + 1;
					
					// Obtain the first response for the targetId (the ADD response)
					while(!Thread.currentThread().isInterrupted()) {
						synchronized(responses) {
							response = responses.get(id);
						}
						
						if(response != null) {
							if(response.targetId() == targetId) {
								list = new ArrayList<>();
								break;
							}
							
							++id;
						} else {
							mtxResponse.await();
						}
					}
					
					// Obtaint all consecutive responses for the targetId
					while(true) {
						synchronized(responses) {
							response = responses.get(id);
						}
						
						if(response != null) {
							if(response.targetId() != targetId) {
								break;
							}
							
							if(response instanceof FirestoreResponse.OfTargetChange) {
								FirestoreResponse.OfTargetChange targetChange
									= (FirestoreResponse.OfTargetChange) response;
								
								if(targetChange.targetChangeType()
										== FirestoreResponse.OfTargetChange.TargetChangeType.CURRENT) {
									// We can send the request to remove the target now since we confirmed that
									// we are in the correct and active target.
									maybeRemoveTarget(targetId);
								}
							}
							
							list.add(responses.remove(id++));
						} else {
							if(currentTargetId != targetId) {
								break;
							}
							
							mtxResponse.await();
						}
					}
					
					return list;
				}
				
				public final void open() {
					mtxSend.unlock();
					startThread();
				}
				
				public final void close() throws IOException {
					try {
						closeThread();
					} catch(IOException ex) {
						// Ignore
					}
				}
				
				public final void throwIfException() throws Exception {
					if(exception != null) {
						throw exception;
					}
				}
			}
			
			private final class DataPuller implements AutoCloseable {
				
				private final Reader reader;
				
				public DataPuller(InputStream stream) {
					this.reader = new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8);
				}
				
				public List<FirestoreResponse.OfContent> pull() throws IOException {
					JSONCollection responses = parseResponse(reader);
					
					if(responses == null) {
						return null; // Error
					}
					
					List<FirestoreResponse.OfContent> list = new ArrayList<>(responses.length());
					
					for(JSONCollection response : responses.collectionsIterable()) {
						list.add(FirestoreResponse.OfContent.create(response));
					}
					
					return list;
				}
				
				@Override
				public void close() throws IOException {
					reader.close();
				}
			}
		}
		
		protected static final class Session {
			
			private final String idToken;
			private final String sessionId;
			private final String sid;
			
			public Session(String idToken, String sessionId, String sid) {
				this.idToken = idToken;
				this.sessionId = sessionId;
				this.sid = sid;
			}
			
			@SuppressWarnings("unused")
			public String idToken() {
				return idToken;
			}
			
			public String sessionId() {
				return sessionId;
			}
			
			public String sid() {
				return sid;
			}
		}
		
		protected static final class StructuredQuery {
			
			private final String parent;
			private final JSONCollection data;
			
			private StructuredQuery(String parent, JSONCollection data) {
				this.parent = Objects.requireNonNull(parent);
				this.data = Objects.requireNonNull(data);
			}
			
			public String parent() {
				return parent;
			}
			
			public JSONCollection data() {
				return data;
			}
			
			protected static final class Builder {
				
				private String parent;
				private JSONCollection data;
				private JSONCollection from;
				private JSONCollection orderBy;
				private JSONCollection where;
				
				private Builder() {
					clear();
				}
		
				private final void clear() {
					parent = null;
					data = JSONCollection.empty();
					from = null;
					orderBy = null;
					where = null;
				}
				
				public StructuredQuery build() {
					StructuredQuery sq = new StructuredQuery(parent, data);
					clear();
					return sq;
				}
				
				public Builder parent(String parent) {
					this.parent = parent;
					return this;
				}
				
				public Builder from(From what) {
					Objects.requireNonNull(what);
					
					if(from == null) {
						from = JSONCollection.emptyArray();
						data.set("from", from);
					}
					
					from.add(what.toCollection());
					return this;
				}
				
				public Builder where(Where what) {
					Objects.requireNonNull(what);
					
					if(where == null) {
						where = JSONCollection.empty();
						data.set("where", where);
					}
					
					where.set(what.propertyName(), what.toCollection());
					return this;
				}
				
				public Builder orderBy(OrderBy what) {
					Objects.requireNonNull(what);
					
					if(orderBy == null) {
						orderBy = JSONCollection.emptyArray();
						data.set("orderBy", orderBy);
					}
					
					orderBy.add(what.toCollection());
					return this;
				}
				
				public Builder limit(int limit) {
					data.set("limit", limit);
					return this;
				}
			}
			
			protected static interface CollectionRepresentable {
				
				JSONCollection toCollection();
			}
			
			protected static abstract class From implements CollectionRepresentable {
				
				protected From() {
				}
				
				protected static final class Collection extends From {
					
					private final String collectionId;
					
					public Collection(String collectionId) {
						this.collectionId = collectionId;
					}
					
					@Override
					public JSONCollection toCollection() {
						JSONCollection data = JSONCollection.empty();
						data.set("collectionId", collectionId);
						return data;
					}
				}
			}
			
			protected static abstract class OrderBy implements CollectionRepresentable {
				
				protected OrderBy() {
				}
				
				protected static enum Direction {
					
					ASCENDING, DESCENDING;
				}
				
				protected static final class Field extends OrderBy {
					
					private final String fieldPath;
					private final Direction direction;
					
					public Field(String fieldPath, Direction direction) {
						this.fieldPath = Objects.requireNonNull(fieldPath);
						this.direction = Objects.requireNonNull(direction);
					}
					
					@Override
					public JSONCollection toCollection() {
						JSONCollection data = JSONCollection.empty();
						data.set("field.fieldPath", fieldPath);
						data.set("direction", direction.name());
						return data;
					}
				}
			}
			
			protected static abstract class Where implements CollectionRepresentable {
				
				protected Where() {
				}
				
				public abstract String propertyName();
				
				protected static enum Operation {
					
					EQUAL, IN, ARRAY_CONTAINS, ARRAY_CONTAINS_ANY;
				}
				
				protected static enum LogicalOperation {
					
					AND, OR;
				}
				
				protected static enum ValueType {
					
					STRING("stringValue"),
					INTEGER("integerValue"),
					REFERENCE("referenceValue"),
					ARRAY("arrayValue");
					
					private final String propertyName;
					
					private ValueType(String propertyName) {
						this.propertyName = propertyName;
					}
					
					public String propertyName() {
						return propertyName;
					}
				}
				
				protected static abstract class SimpleValue {
					
					protected final ValueType valueType;
					
					protected SimpleValue(ValueType valueType) {
						this.valueType = Objects.requireNonNull(valueType);
					}
					
					protected abstract void setValue(JSONCollection data, String name);
					
					@SuppressWarnings("unused")
					protected static final class OfString extends SimpleValue {
						
						private final String value;
						
						public OfString(String value) {
							super(ValueType.STRING);
							this.value = Objects.requireNonNull(value);
						}
						
						@Override
						protected void setValue(JSONCollection data, String name) {
							data.set(name, value);
						}
					}
					
					protected static final class OfReference extends SimpleValue {
						
						private final String value;
						
						public OfReference(String value) {
							super(ValueType.REFERENCE);
							this.value = Objects.requireNonNull(value);
						}
						
						@Override
						protected void setValue(JSONCollection data, String name) {
							data.set(name, value);
						}
					}
				}
				
				protected static abstract class FieldValue extends Where {
					
					protected final String fieldPath;
					protected final Operation operation;
					protected final ValueType valueType;
					
					protected FieldValue(String fieldPath, Operation operation, ValueType valueType) {
						this.fieldPath = Objects.requireNonNull(fieldPath);
						this.operation = Objects.requireNonNull(operation);
						this.valueType = Objects.requireNonNull(valueType);
					}
					
					protected abstract void setValue(JSONCollection data, String name);
					
					@Override
					public String propertyName() {
						return "fieldFilter";
					}
					
					@Override
					public JSONCollection toCollection() {
						JSONCollection data = JSONCollection.empty();
						data.set("field.fieldPath", fieldPath);
						data.set("op", operation.name());
						setValue(data, "value." + valueType.propertyName());
						return data;
					}
				}
				
				protected static final class FieldReference extends FieldValue {
					
					private final String value;
					
					public FieldReference(String fieldPath, Operation operation, String value) {
						super(fieldPath, operation, ValueType.REFERENCE);
						this.value = Objects.requireNonNull(value);
					}
					
					@Override
					protected void setValue(JSONCollection data, String name) {
						data.set(name, value);
					}
				}
				
				protected static final class FieldString extends FieldValue {
					
					private final String value;
					
					public FieldString(String fieldPath, Operation operation, String value) {
						super(fieldPath, operation, ValueType.STRING);
						this.value = Objects.requireNonNull(value);
					}
					
					@Override
					protected void setValue(JSONCollection data, String name) {
						data.set(name, value);
					}
				}
				
				protected static final class FieldInteger extends FieldValue {
					
					private final int value;
					
					public FieldInteger(String fieldPath, Operation operation, int value) {
						super(fieldPath, operation, ValueType.INTEGER);
						this.value = value;
					}
					
					@Override
					protected void setValue(JSONCollection data, String name) {
						data.set(name, String.valueOf(value));
					}
				}
				
				protected static final class FieldArray extends FieldValue {
					
					private final List<SimpleValue> values;
					
					public FieldArray(String fieldPath, Operation operation, SimpleValue... values) {
						this(fieldPath, operation, List.of(values));
					}
					
					public FieldArray(String fieldPath, Operation operation, List<SimpleValue> values) {
						super(fieldPath, operation, ValueType.ARRAY);
						this.values = Objects.requireNonNull(values);
					}
					
					@Override
					protected void setValue(JSONCollection data, String name) {
						JSONCollection collection = JSONCollection.emptyArray();
						
						for(SimpleValue value : values) {
							JSONCollection item = JSONCollection.empty();
							value.setValue(item, value.valueType.propertyName());
							collection.add(item);
						}
						
						data.set(name + ".values", collection);
					}
				}
				
				protected static final class Composite extends Where {
					
					protected final LogicalOperation operation;
					protected final List<Where> filters;
					
					protected Composite(LogicalOperation operation, Where... filters) {
						this(operation, List.of(filters));
					}
					
					protected Composite(LogicalOperation operation, List<Where> filters) {
						this.operation = Objects.requireNonNull(operation);
						this.filters = Objects.requireNonNull(filters);
					}
					
					@Override
					public String propertyName() {
						return "compositeFilter";
					}
					
					@Override
					public JSONCollection toCollection() {
						JSONCollection data = JSONCollection.empty();
						data.set("op", operation.name());
						JSONCollection array = JSONCollection.emptyArray();
						for(Where filter : filters) {
							JSONCollection item = JSONCollection.empty();
							item.set(filter.propertyName(), filter.toCollection());
							array.add(item);
						}
						data.set("filters", array);
						return data;
					}
				}
			}
		}
		
		protected static abstract class FirestoreResponse {
			
			private final int lastId;
			
			protected FirestoreResponse(int lastId) {
				this.lastId = lastId;
			}
			
			public int lastId() {
				return lastId;
			}
			
			protected static final class OfReference extends FirestoreResponse {
				
				private final int requestTargetId;
				
				private OfReference(int requestTargetId, int lastId) {
					super(lastId);
					this.requestTargetId = requestTargetId;
				}
				
				public static final OfReference create(int requestTargetId, JSONCollection data) {
					if(data == null) {
						return null;
					}
					
					int lastId = data.getInt(1);
					
					if(lastId <= 0) {
						throw new IllegalArgumentException("Invalid response ID");
					}
					
					return new OfReference(requestTargetId, lastId);
				}
				
				public int requestTargetId() {
					return requestTargetId;
				}
			}
			
			protected static final class OfTargetChange extends OfContent {
				
				public static enum TargetChangeType {
					
					ADD, REMOVE, CURRENT;
				}
				
				private final TargetChangeType targetChangeType;
				private final int targetId;
				
				private OfTargetChange(int lastId, JSONCollection content, TargetChangeType targetChangeType,
						int targetId) {
					super(ContentType.TARGET_CHANGE, lastId, content);
					this.targetChangeType = Objects.requireNonNull(targetChangeType);
					this.targetId = targetId;
				}
				
				public static final OfTargetChange from(int lastId, JSONCollection content) {
					JSONCollection root = content;
					
					if(root == null
							|| (root = root.getCollection(1)) == null
							|| (root = root.getCollection(0)) == null) {
						return null;
					}
					
					JSONCollection collection = root.getCollection("targetChange");
					
					if(collection == null
							|| !collection.hasString("targetChangeType")) {
						return null;
					}
					
					String typeName = collection.getString("targetChangeType");
					TargetChangeType targetChangeType = Arrays.stream(TargetChangeType.values())
						.filter((e) -> e.name().equals(typeName))
						.findFirst().orElse(null);
					
					if(targetChangeType == null) {
						return null;
					}
					
					int targetId = collection.getCollection("targetIds").getInt(0);
					return new OfTargetChange(lastId, content, targetChangeType, targetId);
				}
				
				public TargetChangeType targetChangeType() {
					return targetChangeType;
				}
				
				public int targetId() {
					return targetId;
				}
			}
			
			protected static final class OfDocumentChange extends OfContent {
				
				private final JSONCollection document;
				
				private OfDocumentChange(int lastId, JSONCollection content, JSONCollection document) {
					super(ContentType.DOCUMENT_CHANGE, lastId, content);
					this.document = Objects.requireNonNull(document);
				}
				
				public static final OfDocumentChange from(int lastId, JSONCollection content) {
					JSONCollection root = content;
					
					if(root == null
							|| (root = root.getCollection(1)) == null
							|| (root = root.getCollection(0)) == null) {
						return null;
					}
					
					JSONCollection collection = root.getCollection("documentChange");
					
					if(collection == null
							|| !collection.hasCollection("document")) {
						return null;
					}
					
					JSONCollection document = collection.getCollection("document");
					return new OfDocumentChange(lastId, content, document);
				}
				
				public JSONCollection document() {
					return document;
				}
			}
			
			protected static class OfContent extends FirestoreResponse {
				
				public static enum ContentType {
					
					TARGET_CHANGE, DOCUMENT_CHANGE, OTHER, UNKNOWN;
				}
				
				protected final ContentType type;
				protected final JSONCollection content;
				protected final int targetId;
				
				protected OfContent(ContentType type, int lastId, JSONCollection content) {
					super(lastId);
					this.type = Objects.requireNonNull(type);
					this.content = Objects.requireNonNull(content);
					this.targetId = extractTargetId(content);
				}
				
				private static final int extractTargetId(JSONCollection content) {
					for(JSONCollection collection : content.getCollection(1).collectionsIterable()) {
						for(JSONCollection child : collection.collectionsIterable()) {
							JSONCollection targets = child.getCollection("targetIds");
							
							if(targets == null || targets.length() == 0) {
								continue;
							}
							
							return targets.objectsIterator().next().intValue();
						}
					}
					
					return 0;
				}
				
				private static final ContentType extractContentType(JSONCollection data) {
					JSONCollection root = data;
					
					if(root == null
							|| (root = root.getCollection(1)) == null
							|| (root = root.getCollection(0)) == null) {
						return ContentType.UNKNOWN;
					}
					
					Iterator<JSONCollection> iterator = root.collectionsIterator();
					
					if(!iterator.hasNext()) {
						return ContentType.UNKNOWN;
					}
					
					JSONCollection first = iterator.next();
					switch(first.name()) {
						case "targetChange": return ContentType.TARGET_CHANGE;
						case "documentChange": return ContentType.DOCUMENT_CHANGE;
						default: return ContentType.OTHER;
					}
				}
				
				public static final OfContent create(JSONCollection data) {
					if(data == null) {
						return null;
					}
					
					ContentType type = extractContentType(data);
					int lastId = data.getInt(0);
					OfContent instance = null;
					
					switch(type) {
						case TARGET_CHANGE: instance = OfTargetChange.from(lastId, data); break;
						case DOCUMENT_CHANGE: instance = OfDocumentChange.from(lastId, data); break;
						default: break; // Do nothing
					}
					
					if(instance == null) {
						instance = new OfContent(ContentType.OTHER, lastId, data);
					}
					
					return instance;
				}
				
				public ContentType type() {
					return type;
				}
				
				@SuppressWarnings("unused")
				public JSONCollection content() {
					return content;
				}
				
				public int targetId() {
					return targetId;
				}
			}
		}
	}
}