package sune.app.mediadown.downloader.wms;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import sune.app.mediadown.Shared;
import sune.app.mediadown.download.FileDownloader;
import sune.app.mediadown.event.tracker.TrackerManager;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaMimeType;
import sune.app.mediadown.util.Pair;
import sune.app.mediadown.util.Ref;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;

public final class SegmentedSubtitlesDownloader extends FileDownloader {
	
	private static final Set<String> SUPPORTED_MIME_TYPES;
	
	static {
		SUPPORTED_MIME_TYPES = List.of(MediaFormat.VTT).stream()
			.flatMap((f) -> f.mimeTypes().stream())
			.collect(Collectors.toUnmodifiableSet());
	}
	
	private final SubtitlesRetimeStrategy retimeStrategy;
	private boolean first = true;
	
	public SegmentedSubtitlesDownloader(TrackerManager trackerManager, SubtitlesRetimeStrategy retimeStrategy) {
		super(trackerManager);
		this.retimeStrategy = Objects.requireNonNull(retimeStrategy);
	}
	
	private static final boolean isContentSupported(String contentType) {
		return SUPPORTED_MIME_TYPES.contains(MediaMimeType.fromString(contentType).typeAndSubtype());
	}
	
	private final boolean isContentSupported() {
		return response.headers()
			.firstValue("Content-Type")
			.filter(SegmentedSubtitlesDownloader::isContentSupported)
			.isPresent();
	}
	
	private final InputStream createResponseStream(InputStream stream) {
		boolean includeMagic = first;
		first = false;
		
		InputStream responseStream = retimeStrategy.create(stream, includeMagic);
		
		if(responseStream == null) {
			responseStream = stream;
		}
		
		return responseStream;
	}
	
	@Override
	protected boolean shouldModifyResponseStream(InputStream stream) throws Exception {
		return isContentSupported();
	}
	
	@Override
	protected InputStream modifyResponseStream(InputStream stream) throws Exception {
		return createResponseStream(stream);
	}
	
	public static interface SubtitlesRetimeStrategy {
		
		default void load(MediaMetadata metadata) { /* Do nothing */ }
		InputStream create(InputStream stream, boolean includeMagic);
	}
	
	public static final class SubtitlesRetimeStrategies {
		
		private static final ClassRegistry<SubtitlesRetimeStrategy> REGISTRY = new ClassRegistry<>();
		
		static {
			register("none", NoneSubtitlesRetimeStrategy.class);
			register("startTime", StartTimeSubtitlesRetimeStrategy.class);
			register("startAtZero", StartAtZeroSubtitlesRetimeStrategy.class);
		}
		
		private SubtitlesRetimeStrategies() {
		}
		
		public static final void register(String name, Class<? extends SubtitlesRetimeStrategy> clazz) {
			REGISTRY.add(name, clazz);
		}
		
		public static final SubtitlesRetimeStrategy of(String name) {
			return REGISTRY.instance(name);
		}
		
		public static final SubtitlesRetimeStrategy of(MediaMetadata metadata) {
			Objects.requireNonNull(metadata);
			
			String name = metadata.get("subtitles.retime.strategy", "none");
			SubtitlesRetimeStrategy retimeStrategy = of(name);
			
			if(retimeStrategy == null) {
				retimeStrategy = of("none");
			}
			
			retimeStrategy.load(metadata);
			return retimeStrategy;
		}
		
		private static final class ClassRegistry<T> {
			
			private final Map<String, Class<?>> registry = new HashMap<>();
			
			public void add(String name, Class<? extends T> clazz) {
				registry.put(Objects.requireNonNull(name), Objects.requireNonNull(clazz));
			}
			
			private final Object newInstance(Class<?> clazz) {
				try {
					Constructor<?> ctor = clazz.getDeclaredConstructor();
					return ctor.newInstance();
				} catch(NoSuchMethodException
							| IllegalArgumentException
							| IllegalAccessException
							| InstantiationException
							| InvocationTargetException
							| SecurityException ex) {
					throw new RuntimeException(ex);
				}
			}
			
			public Class<? extends T> get(String name) {
				@SuppressWarnings("unchecked")
				Class<? extends T> clazz = (Class<? extends T>) registry.get(name);
				return clazz;
			}
			
			public T instance(String name) {
				Class<? extends T> clazz = get(name);
				
				if(clazz == null) {
					return null;
				}
				
				@SuppressWarnings("unchecked")
				T instance = (T) newInstance(clazz);
				return instance;
			}
		}
	}
	
	// Reference: https://www.w3.org/TR/webvtt1/#webvtt-timestamp
	public static final class VTTTimestamp {
		
		private static final long FRACTION = 1000L;
		private static final long SECONDS = 60L;
		private static final long MINUTES = 60L;
		private static final long DIV_SECONDS = FRACTION;
		private static final long DIV_MINUTES = SECONDS * DIV_SECONDS;
		private static final long DIV_HOURS = MINUTES * DIV_MINUTES;
		
		private static final VTTTimestamp ZERO = new VTTTimestamp(0L);
		
		private static final Regex REGEX = Regex.of(
			"^\\s*(?:(?<h>[0-9]{2,}):)?(?<m>[0-5][0-9]):(?<s>[0-5][0-9])\\.(?<f>[0-9]{3})\\s*$"
		);
		
		private final long value;
		
		private VTTTimestamp(long value) {
			this.value = value;
		}
		
		private VTTTimestamp(int hours, int minutes, int seconds, int fraction) {
			this(valueChecked(hours, minutes, seconds, fraction));
		}
		
		private static final long value(int hours, int minutes, int seconds, int fraction) {
			return ((hours * MINUTES + minutes) * SECONDS + seconds) * FRACTION + fraction;
		}
		
		private static final long valueChecked(int hours, int minutes, int seconds, int fraction) {
			return value(hours, checkMinutes(minutes), checkSeconds(seconds), checkFraction(fraction));
		}
		
		private static final int checkMinutes(int value) {
			if(value < 0 || value >= 60) {
				throw new IllegalArgumentException("Minutes must be in range <0, 59>");
			}
			
			return value;
		}
		
		private static final int checkSeconds(int value) {
			if(value < 0 || value >= 60) {
				throw new IllegalArgumentException("Seconds must be in range <0, 59>");
			}
			
			return value;
		}
		
		private static final int checkFraction(int value) {
			if(value < 0 || value >= 1000) {
				throw new IllegalArgumentException("Seconds must be in range <0, 999>");
			}
			
			return value;
		}
		
		public static final VTTTimestamp of(String string) {
			Matcher matcher = REGEX.matcher(string);
			
			if(!matcher.matches()) {
				throw new IllegalArgumentException("Invalid timestamp");
			}
			
			String hours = matcher.group("h");
			String minutes = matcher.group("m");
			String seconds = matcher.group("s");
			String fraction = matcher.group("f");
			
			if(hours == null) {
				hours = "0";
			}
			
			return new VTTTimestamp(
				Integer.valueOf(hours),
				Integer.valueOf(minutes),
				Integer.valueOf(seconds),
				Integer.valueOf(fraction)
			);
		}
		
		public static final VTTTimestamp of(int hours, int minutes, int seconds, int fraction) {
			return new VTTTimestamp(hours, minutes, seconds, fraction);
		}
		
		public static final VTTTimestamp ofZero() {
			return ZERO;
		}
		
		public VTTTimestamp add(VTTTimestamp other) {
			Objects.requireNonNull(other);
			return other == ZERO ? this : new VTTTimestamp(value + other.value);
		}
		
		public VTTTimestamp subtract(VTTTimestamp other) {
			Objects.requireNonNull(other);
			return other == ZERO ? this : new VTTTimestamp(value - other.value);
		}
		
		public VTTTimestamp nonNegative() {
			return value < 0L ? ZERO : this;
		}
		
		public VTTTimestamp complement() {
			int hours = -hours() + 1;
			int minutes = (int) MINUTES - minutes() - 1;
			int seconds = (int) SECONDS - seconds() - 1;
			int fraction = (int) FRACTION - fraction() - 1;
			return new VTTTimestamp(hours, minutes, seconds, fraction);
		}
		
		public int hours() { return (int) (value / DIV_HOURS); }
		public int minutes() { return (int) Math.abs((value / DIV_MINUTES) % MINUTES); }
		public int seconds() { return (int) Math.abs((value / DIV_SECONDS) % SECONDS); }
		public int fraction() { return (int) Math.abs(value % FRACTION); }
		
		@Override
		public String toString() {
			return String.format("%02d:%02d:%02d.%03d", hours(), minutes(), seconds(), fraction());
		}
	}
	
	private static final class NoneSubtitlesRetimeStrategy implements SubtitlesRetimeStrategy {
		
		@Override
		public InputStream create(InputStream stream, boolean includeMagic) {
			return new SegmentedVTTInputStream(stream, includeMagic);
		}
	}
	
	private static final class StartTimeSubtitlesRetimeStrategy implements SubtitlesRetimeStrategy {
		
		private VTTTimestamp startTime;
		
		@Override
		public void load(MediaMetadata metadata) {
			startTime = Optional.ofNullable(metadata.<String>get("subtitles.retime.startTime"))
				.map(VTTTimestamp::of)
				.orElse(VTTTimestamp.ofZero());
		}
		
		@Override
		public InputStream create(InputStream stream, boolean includeMagic) {
			return new InputStreamImpl(stream, includeMagic);
		}
		
		private final class InputStreamImpl extends SegmentedVTTInputStream {
			
			public InputStreamImpl(InputStream stream, boolean includeMagic) {
				super(stream, includeMagic);
			}
			
			@Override
			protected Pair<VTTTimestamp, VTTTimestamp> modifyCueTimestamps(VTTTimestamp from, VTTTimestamp to) {
				from = from.subtract(startTime);
				to = to.subtract(startTime);
				return new Pair<>(from, to);
			}
		}
	}
	
	private static final class StartAtZeroSubtitlesRetimeStrategy implements SubtitlesRetimeStrategy {
		
		private final Ref.Mutable<VTTTimestamp> firstTimeRef = new Ref.Mutable<>(null);
		
		private VTTTimestamp startTime;
		private boolean ignoreHours;
		
		@Override
		public void load(MediaMetadata metadata) {
			startTime = Optional.ofNullable(metadata.<String>get("subtitles.retime.startTime"))
				.map(VTTTimestamp::of)
				.orElse(VTTTimestamp.ofZero());
			ignoreHours = metadata.get("subtitles.retime.ignoreHours", false);
		}
		
		@Override
		public InputStream create(InputStream stream, boolean includeMagic) {
			return new InputStreamImpl(stream, includeMagic);
		}
		
		private final class InputStreamImpl extends SegmentedVTTInputStream {
			
			public InputStreamImpl(InputStream stream, boolean includeMagic) {
				super(stream, includeMagic);
			}
			
			@Override
			protected Pair<VTTTimestamp, VTTTimestamp> modifyCueTimestamps(VTTTimestamp from, VTTTimestamp to) {
				VTTTimestamp firstTime = firstTimeRef.get();
				
				if(firstTime == null) {
					firstTime = from;
					
					if(startTime != null) {
						int hours = ignoreHours ? from.hours() : startTime.hours();
						firstTime = VTTTimestamp.of(
							hours, startTime.minutes(), startTime.seconds(), startTime.fraction()
						);
					}
					
					firstTimeRef.set(firstTime);
				}
				
				from = from.subtract(firstTime);
				to = to.subtract(firstTime);
				return new Pair<>(from, to);
			}
		}
	}
	
	// Reference: https://www.w3.org/TR/webvtt1/#file-structure
	private static class SegmentedVTTInputStream extends InputStream {
		
		private static final String MAGIC = "WEBVTT";
		
		private static final int STATE_NONE = 0;
		private static final int STATE_HEADER = 1;
		private static final int STATE_CONTENT = 2;
		private static final int STATE_REGION = 3;
		private static final int STATE_STYLE = 4;
		private static final int STATE_COMMENT = 5;
		private static final int STATE_CUE = 6;
		
		private final InputStream stream;
		private final boolean includeMagic;
		
		private BufferedReader reader;
		private BufferedWriter writer;
		private InputStream modified;
		private boolean initialized;
		private int state = STATE_NONE;
		private int lineTerminatorCtr;
		private boolean written;
		
		public SegmentedVTTInputStream(InputStream stream, boolean includeMagic) {
			this.stream = stream;
			this.includeMagic = includeMagic;
		}
		
		private final void initialize() throws IOException {
			PipedOutputStream out = new PipedOutputStream();
			reader = new BufferedReader(new InputStreamReader(stream, Shared.CHARSET));
			writer = new BufferedWriter(new OutputStreamWriter(out, Shared.CHARSET));
			modified = new PipedInputStream(out);
			initialized = true;
		}
		
		private final String readLine() throws IOException {
			return reader.readLine();
		}
		
		private final void writeLine(String line) throws IOException {
			if(!line.isEmpty()) {
				writer.write(line);
			}
			
			writer.newLine();
			written = true;
		}
		
		private final void flush() throws IOException {
			writer.flush();
		}
		
		private final void clean() {
			written = false;
		}
		
		private final void wrongVTTFormat() {
			throw new IllegalStateException("Wrong VTT format");
		}
		
		private final boolean readMagic(String line) throws IOException {
			if(!line.startsWith(MAGIC)) {
				throw new IllegalStateException("Missing magic");
			}
			
			if(includeMagic) {
				// Keep the original line
				writeLine(line);
			}
			
			state = STATE_HEADER;
			return true;
		}
		
		private final boolean readHeader(String line) throws IOException {
			// Ignore all lines
			return true;
		}
		
		private final boolean readRegion(String line) throws IOException {
			// Keep all lines
			writeLine(line);
			return true;
		}
		
		private final boolean readStyle(String line) throws IOException {
			// Keep all lines
			writeLine(line);
			return true;
		}
		
		private final boolean readComment(String line) throws IOException {
			// Keep all lines
			writeLine(line);
			return true;
		}
		
		private final boolean readCue(String line) throws IOException {
			if(!line.contains("-->")) {
				writeLine(line); // Keep cue identifiers and cue themselves
				return true;
			}
			
			String[] parts = Utils.OfString.split(line, "-->", 2);
			
			if(parts.length != 2) {
				wrongVTTFormat();
			}
			
			VTTTimestamp from = VTTTimestamp.of(parts[0]);
			VTTTimestamp to = VTTTimestamp.of(parts[1]);
			Pair<VTTTimestamp, VTTTimestamp> modified = modifyCueTimestamps(from, to);
			
			// Allow no modification by just returning null
			if(modified != null) {
				from = modified.a;
				to = modified.b;
			}
			
			writeLine(from + " --> " + to);
			return true;
		}
		
		private final int transitionStateEmptyLine() throws IOException {
			switch(state) {
				case STATE_HEADER:
				case STATE_REGION:
				case STATE_STYLE:
				case STATE_COMMENT:
				case STATE_CUE:
					if(lineTerminatorCtr < 1)
						return state;
					
					return STATE_CONTENT;
				default:
					return state;
			}
		}
		
		private final int transitionState(String line) throws IOException {
			switch(state) {
				case STATE_CONTENT:
					if(line.startsWith("REGION"))
						return STATE_REGION;
					if(line.startsWith("STYLE"))
						return STATE_STYLE;
					if(line.startsWith("NOTE"))
						return STATE_COMMENT;
					
					return STATE_CUE;
				default:
					return state;
			}
		}
		
		private final boolean processState(String line) throws IOException {
			switch(state) {
				case STATE_NONE: return readMagic(line);
				case STATE_HEADER: return readHeader(line);
				case STATE_REGION: return readRegion(line);
				case STATE_STYLE: return readStyle(line);
				case STATE_COMMENT: return readComment(line);
				case STATE_CUE: return readCue(line);
				default: wrongVTTFormat(); return false;
			}
		}
		
		private final boolean processNextLine() throws IOException {
			String line;
			if((line = readLine()) == null) {
				return false; // EOS
			}
			
			if(line.isEmpty()) {
				++lineTerminatorCtr;
				state = transitionStateEmptyLine();
				writeLine(""); // Keep empty lines
				return true;
			}
			
			state = transitionState(line);
			boolean ok = processState(line);
			lineTerminatorCtr = 0;
			return ok;
		}
		
		protected Pair<VTTTimestamp, VTTTimestamp> modifyCueTimestamps(VTTTimestamp from, VTTTimestamp to) {
			return null; // By default do not modify
		}
		
		@Override
		public int read() throws IOException {
			if(!initialized) {
				initialize();
			}
			
			// Check whether there are no available data to be read.
			// Zero may also mean that an error occurred or that the stream is closed/broken.
			// However, this will be checked in the processNextLine() method. 
			if(modified.available() == 0) {
				do {
					if(!processNextLine()) {
						return -1; // EOS
					}
				} while(!written);
				
				flush();
				clean();
			}
			
			return modified.read();
		}
		
		@Override
		public void close() throws IOException {
			IOException exception = null;
			
			try {
				if(reader != null) {
					try {
						reader.close();
					} catch(IOException ex) {
						exception = ex;
					}
				}
				
				if(writer != null) {
					try {
						writer.close();
					} catch(IOException ex) {
						exception = ex;
					}
				}
				
				if(modified != null) {
					try {
						modified.close();
					} catch(IOException ex) {
						exception = ex;
					}
				}
				
				if(exception != null) {
					throw exception;
				}
			} finally {
				reader = null;
				writer = null;
				modified = null;
			}
		}
	}
}