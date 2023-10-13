package sune.app.mediadown.media_engine.tvbarrandov;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.authentication.CredentialsManager;
import sune.app.mediadown.authentication.UsernameCredentials;
import sune.app.mediadown.entity.Episode;
import sune.app.mediadown.entity.MediaEngine;
import sune.app.mediadown.entity.MediaGetter;
import sune.app.mediadown.entity.MediaGetters;
import sune.app.mediadown.entity.Program;
import sune.app.mediadown.gui.control.IntegerTextField;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.media.Media;
import sune.app.mediadown.media.MediaConstants;
import sune.app.mediadown.media.MediaFormat;
import sune.app.mediadown.media.MediaMetadata;
import sune.app.mediadown.media.MediaQuality;
import sune.app.mediadown.media.MediaSource;
import sune.app.mediadown.media.MediaType;
import sune.app.mediadown.media.MediaUtils;
import sune.app.mediadown.media.VideoMedia;
import sune.app.mediadown.net.HTML;
import sune.app.mediadown.net.Net;
import sune.app.mediadown.net.Web;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.net.Web.Response;
import sune.app.mediadown.plugin.PluginBase;
import sune.app.mediadown.plugin.PluginLoaderContext;
import sune.app.mediadown.task.ListTask;
import sune.app.mediadown.util.FXUtils;
import sune.app.mediadown.util.JSON;
import sune.app.mediadown.util.JSON.JSONCollection;
import sune.app.mediadown.util.JavaScript;
import sune.app.mediadown.util.Opt;
import sune.app.mediadown.util.Regex;
import sune.app.mediadown.util.Utils;
import sune.app.mediadown.util.Utils.Ignore;

public final class TVBarrandovEngine implements MediaEngine {
	
	private static final PluginBase PLUGIN = PluginLoaderContext.getContext().getInstance();
	
	public static final String TITLE   = PLUGIN.getTitle();
	public static final String VERSION = PLUGIN.getVersion();
	public static final String AUTHOR  = PLUGIN.getAuthor();
	public static final String URL     = PLUGIN.getURL();
	public static final Image  ICON    = PLUGIN.getIcon();
	
	// URLs
	private static final String URL_PROGRAMS = "https://www.barrandov.tv/porady/";
	
	// Selectors
	private static final String SELECTOR_GRID     = ".main > .section > .container > .grid > .col";
	private static final String SELECTOR_PROGRAMS = SELECTOR_GRID;
	private static final String SELECTOR_EPISODES = SELECTOR_GRID + " > .show-box:not(.show-box--date)";
	
	private static Regex REGEX_EPISODE_URL;
	
	// Allow to create an instance when registering the engine
	TVBarrandovEngine() {
	}
	
	private static final String maybeImproveEpisodeTitle(Program program, String url, String title) {
		if(REGEX_EPISODE_URL == null) {
			REGEX_EPISODE_URL = Regex.of("/video/\\d+((?:-[^-]+)+)-(\\d{1,2})-(\\d{1,2})-(\\d{4})$");
		}
		
		Matcher matcher = REGEX_EPISODE_URL.matcher(url);
		if(matcher.find()) {
			// Try to convert the program's title to URL-like text
			String normalizedName = Utils.normalize(program.title()).replaceAll("\\s+", "-").toLowerCase();
			String extractedName = matcher.group(1).replaceFirst("^-", "");
			// Check whether there is any more text for the episode title
			if(!normalizedName.equals(extractedName)) {
				// Try to beautify the episode title in the URL
				extractedName = extractedName.replaceFirst("^" + Regex.quote(normalizedName), "");
				extractedName = extractedName.replaceFirst("^-", "");
				extractedName = extractedName.replaceAll("-", " ");
				if(!extractedName.isEmpty()) {
					extractedName = Utils.titlize(extractedName);
					title = String.format("%s (%s)", extractedName, title);
				}
			}
		}
		
		return title;
	}
	
	private static final boolean parseEpisodesPage(ListTask<Episode> task, Program program, Document document)
			throws Exception {
		for(Element elEpisode : document.select(SELECTOR_EPISODES)) {
			String url = elEpisode.selectFirst("a.show-box__container").absUrl("href");
			String title = maybeImproveEpisodeTitle(program, url, elEpisode.selectFirst(".show-box__timestamp").text());
			Episode episode = new Episode(program, Net.uri(url), title);
			
			if(!task.add(episode)) {
				return false; // Do not continue
			}
		}
		
		return true;
	}
	
	@Override
	public ListTask<Program> getPrograms() throws Exception {
		return ListTask.of((task) -> {
			Document document = HTML.from(Net.uri(URL_PROGRAMS));
			
			for(Element elProgram : document.select(SELECTOR_PROGRAMS)) {
				if(elProgram.selectFirst(".show-box") == null) continue;
				String url = elProgram.selectFirst("a.show-box__container").absUrl("href");
				String title = elProgram.selectFirst(".show-box__title").text();
				Program program = new Program(Net.uri(url), title);
				
				if(!task.add( program)) {
					return; // Do not continue
				}
			}
		});
	}
	
	@Override
	public ListTask<Episode> getEpisodes(Program program) throws Exception {
		return ListTask.of((task) -> {
			URI baseURI = program.uri();
			Document document = HTML.from(Net.resolve(baseURI, baseURI.getPath() + "/video?page=1"));
			EpisodesObtainStrategy strategy;
			
			// Obtain the range of available pages
			int first = 1, last = 1;
			Element elPagination = document.selectFirst(".pagination__pages");
			if(elPagination != null) {
				Element elPageFirst = elPagination.selectFirst("> :nth-child(2)");
				Element elPageLast  = elPagination.selectFirst("> :nth-last-child(2)");
				first = Optional.ofNullable(elPageFirst).map(Element::text).map(Integer::valueOf).orElse(-1);
				last  = Optional.ofNullable(elPageLast) .map(Element::text).map(Integer::valueOf).orElse(-1);
				if(first < 0) first = 1;
				if(last  < 0) last  = first;
			}
			
			final int maxNumOfPagesToGetAll = 5;
			if(last - first >= maxNumOfPagesToGetAll) {
				final int minPage = first, maxPage = last;
				strategy = FXUtils.fxTaskValue(() -> (new EpisodesObtainStrategyDialog(program, minPage, maxPage, document))
				                                         .showAndWait())
						          .orElse(null);
			} else {
				strategy = new PageRangeEpisodesObtainStrategy(program, first + 1, last, document);
			}
			
			// If the strategy chooser dialog was closed, do not continue
			if(strategy == null) {
				return;
			}
			
			// Run the strategy and obtain all episodes based on the user's selection
			strategy.obtain(task);
		});
	}
	
	@Override
	public ListTask<Media> getMedia(URI uri, Map<String, Object> data) throws Exception {
		return ListTask.of((task) -> {
			// Try to log in to the user's account, if the details are present
			if(Authenticator.areLoginDetailsPresent() && !Authenticator.authenticate()) {
				throw new IllegalStateException("Unable to log in to the account");
			}
			
			Document document = HTML.from(uri);
			URI uriToProcess = null;
			
			// Check whether we've been redirected to the Premium Archive information page
			if(Net.uri(document.baseUri()).getPath().startsWith("/premiovy-archiv")) {
				// Some videos are marked as premium but are actually available on YouTube,
				// try to search for them on the official YouTube channel.
				String queryURL = "https://www.youtube.com/c/TelevizeBarrandovOfficial/search?query=%{query}s";
				String query = null, programName = null, dateString = null;
				
				Regex regex = Regex.of("^/video/\\d+((?:-[^-]+)+)-(\\d{1,2})-(\\d{1,2})-(\\d{4})$");
				Matcher matcher = regex.matcher(uri.getPath());
				if(matcher.matches()) {
					programName = Utils.titlize(matcher.group(1).substring(1));
					int day = Integer.valueOf(matcher.group(2));
					int month = Integer.valueOf(matcher.group(3));
					int year = Integer.valueOf(matcher.group(4));
					dateString = String.format("%02d.%02d.%04d", day, month, year);
					query = String.format("%s %s", programName, dateString);
				}
				
				if(query != null) {
					boolean tryDayBefore = false;
					do {
						// Construct the query URL for searching the term
						URI requestURI = Net.uri(Utils.format(queryURL, "query", JavaScript.encodeURIComponent(query)));
						Response.OfString response = Web.request(Request.of(requestURI).GET());
						Document searchDocument = HTML.parse(response.body(), response.uri());
						boolean isSearchPage = true;
						
						// If a consent page is shown, automatically bypass it
						if(response.uri().getHost().equals("consent.youtube.com")) {
							isSearchPage = false;
							
							Element elMeta = searchDocument.selectFirst("noscript > meta");
							URI consentURI = Net.uri(Utils.unquote(elMeta.attr("content").split(";", 2)[1].split("=", 2)[1]));
							Document consentDocument = HTML.from(consentURI);
							Map<String, Object> args = new LinkedHashMap<>();
							
							forms:
							for(Element elForm : consentDocument.select(".saveButtonContainer form")) {
								boolean eom = false;
								
								// Parse all hidden inputs of the form
								for(Element elInput : elForm.select("input[type='hidden']")) {
									String name = elInput.attr("name");
									String value = elInput.attr("value");
									
									if(name.equals("set_eom")) {
										if(!value.equals("true")) {
		    								// Wrong form, reset
		    								args.clear();
		    								continue forms;
										}
										eom = true;
									}
									
									args.put(name, value);
								}
								
								// Found the correct form, exit
								if(eom) break;
							}
							
							HttpHeaders headers = Web.Headers.ofSingle(
								"Referer", consentURI.toString()
							);
							
							String body = Net.queryString(args);
							URI postURI = Net.uri("https://consent.youtube.com/save");
							response = Web.request(Request.of(postURI).headers(headers).POST(body));
							
							// Retry the previous search request but now with rejecting the consent
							response = Web.request(Request.of(requestURI).GET());
							searchDocument = HTML.parse(response.body(), response.uri());
							isSearchPage = !response.uri().getHost().equals("consent.youtube.com");
						}
						
						if(isSearchPage) {
							programName = programName.toLowerCase();
							
							// Find the initial page data where there are the first search results
							for(Element elScript : searchDocument.select("script")) {
								String content = elScript.html();
								int index;
								if((index = content.indexOf("ytInitialData = {")) >= 0) {
									content = Utils.bracketSubstring(content, '{', '}', false, index, content.length());
									JSONCollection json = JavaScript.readObject(content);
									
									JSONCollection tabs = json.getCollection("contents.twoColumnBrowseResultsRenderer.tabs");
									JSONCollection searchTab = tabs.getCollection("" + (tabs.length() - 1));
									JSONCollection searchContent = searchTab.collectionsIterator().next()
											.getCollection("content").collectionsIterator().next()
											.getCollection("contents");
									
									for(JSONCollection searchItem : searchContent.collectionsIterable()) {
										JSONCollection itemData = searchItem.collectionsIterator().next()
											.getCollection("contents.0.videoRenderer", null);
										if(itemData == null) continue; // Invalid item, skip it
										
										String videoId = itemData.getString("videoId");
										String title = itemData.getString("title.runs.0.text").toLowerCase();
										// Choose the result we want, it should at least contain the required
										// words, i.e. program name and the date string.
										if(Regex.of("(?U)\\b" + Regex.quote(programName) + "\\b").matcher(title).find()
												&& Regex.of("(?U)\\b" + Regex.quote(dateString) + "\\b").matcher(title).find()) {
											uriToProcess = Net.uri("https://www.youtube.com/watch?v=" + videoId);
											// Video found, do not continue
											break;
										}
									}
									
									// Obtained the needed initial data, do not continue
									break;
								}
							}
						}
						
						// Also some videos are actually linked on the website twice, since some of them
						// are just a replay. Try to get a video from the day before.
						if(uriToProcess == null && !tryDayBefore) {
							DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
							dateString = LocalDate.parse(dateString, formatter).plusDays(-1L).format(formatter);
							tryDayBefore = true;
						} else if(tryDayBefore) {
							tryDayBefore = false; // Must reset the flag, if set
						}
					} while(tryDayBefore);
				}
			}
			
			MediaSource source = MediaSource.of(this);
			Element elVideo;
			
			if((elVideo = document.selectFirst(".main video")) != null) { // Local video
				String programName = "";
				String episodeName = "";
				double duration = MediaConstants.UNKNOWN_DURATION;
				
				// Video metadata are contained in a script tag and variable of name 'metadataObjectContent'
				for(Element elScript : document.select("script")) {
					String content = elScript.html();
					int index = content.indexOf("metadataObjectContent = {");
					if(index >= 0) {
						// Extract the content of JavaScript object
						content = Utils.bracketSubstring(content, '{', '}', false, index, content.length());
						// JSON cannot be parsed with comments, so remove them
						content = JSONUtils.removeComments(content);
						
						// Parse the content of the JavaScript object
						JSONCollection json = JSON.read(content);
						duration = Double.valueOf(json.getString("length", "-1.0"));
						programName = json.getString("program", "");
						episodeName = json.getString("title", "");
						
						// Some videos can have the exactly same name, we don't want double name in the media title
						if(programName.equalsIgnoreCase(episodeName))
							episodeName = "";
						
						// Add information about airdate since almost all videos have that
						String airdate = json.getString("airdate", "");
						if(!airdate.isEmpty()) {
							DateTimeFormatter formatterParse = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
							DateTimeFormatter formatterFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
							airdate = formatterFormat.format(formatterParse.parse(airdate));
							
							boolean hasEpisodeName = !episodeName.isEmpty();
							episodeName += (hasEpisodeName ? " (" : "") + airdate + (hasEpisodeName ? ")" : "");
						}
						
						// All data obtained, do no continue
						break;
					}
				}
				
				// Construct the video media title using user's chosen media title format
				String mediaTitle = MediaUtils.mediaTitle(programName, -1, -1, episodeName);
				MediaMetadata metadata = MediaMetadata.builder().sourceURI(uri).title(mediaTitle).build();
				
				for(Element elSource : elVideo.select("source")) {
					String src = elSource.absUrl("src");
					String res = elSource.attr("res") + 'p';
					String type = elSource.attr("type");
					
					Media media = VideoMedia.simple().source(source)
							.uri(Net.uri(src))
							.format(MediaFormat.fromMimeType(type))
							.quality(MediaQuality.fromString(res, MediaType.VIDEO))
							.duration(duration)
							.metadata(metadata)
							.build();
					
					if(!task.add(media)) {
						return;
					}
				}
			} else if((elVideo = document.selectFirst(".video-responsive > iframe")) != null) { // Embedded video
				uriToProcess = Net.uri(elVideo.attr("src"));
			}
			
			// Check if any URI needs to be processed (used for embedded and searched videos)
			if(uriToProcess != null) {
				// Embedded videos are mostly from YouTube, so if the YouTube plugin is present and active,
				// this should work.
				MediaGetter getter;
				if((getter = MediaGetters.fromURI(uriToProcess)) != null) {
					ListTask<Media> t = getter.getMedia(uriToProcess, Map.of());
					t.forwardAdd(task);
					t.startAndWait();
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
		if(!host.equals("barrandov.tv"))
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
	
	private static abstract class EpisodesObtainStrategy {
		
		protected final Program program;
		
		protected EpisodesObtainStrategy(Program program) {
			this.program = Objects.requireNonNull(program);
		}
		
		public abstract void obtain(ListTask<Episode> task) throws Exception;
	}
	
	private static final class DateRangeEpisodesObtainStrategy extends EpisodesObtainStrategy {
		
		private final LocalDate from;
		private final LocalDate to;
		
		public DateRangeEpisodesObtainStrategy(Program program, LocalDate from, LocalDate to) {
			super(program);
			this.from = Objects.requireNonNull(from);
			this.to = Objects.requireNonNull(to);
		}
		
		@Override
		public void obtain(ListTask<Episode> task) throws Exception {
			URI baseURI = program.uri();
			
			for(LocalDateTime i = from.atStartOfDay(), t = to.atStartOfDay();
					i.compareTo(t) <= 0;
					i = i.plusDays(1L)) {
				long seconds = i.toEpochSecond(ZoneOffset.UTC);
				Document doc = HTML.from(Net.resolve(baseURI, baseURI.getPath() + "/video?showDay=" + seconds));
				if(!parseEpisodesPage(task, program, doc))
					return; // Aborted, do not continue
			}
		}
	}
	
	private static final class PageRangeEpisodesObtainStrategy extends EpisodesObtainStrategy {
		
		private final int from;
		private final int to;
		private final Document firstDocument;
		
		public PageRangeEpisodesObtainStrategy(Program program, int from, int to, Document firstDocument) {
			super(program);
			this.from = checkPage(from);
			this.to = checkPage(to);
			this.firstDocument = firstDocument; // Can be null
		}
		
		private static final int checkPage(int value) {
			if(value <= 0)
				throw new IllegalArgumentException("Invalid page number, must be >= 1");
			return value;
		}
		
		@Override
		public void obtain(ListTask<Episode> task) throws Exception {
			URI baseURI = program.uri();
			
			// Parse the first episodes page, if already obtained
			if(firstDocument != null
					&& !parseEpisodesPage(task, program, firstDocument))
				return; // Aborted, do not continue
			
			// This is probably the fastest way to iterate through all TV Barrandov's episodes pages,
			// since more simultaneous connections result in higher times.
			// As far as I know, there is no API to return all episodes at once.
			for(int i = from; i <= to; ++i) {
				Document doc = HTML.from(Net.resolve(baseURI, baseURI.getPath() + "/video?page=" + i));
				if(!parseEpisodesPage(task, program, doc))
					return; // Aborted, do not continue
			}
		}
	}
	
	private static final class EpisodesObtainStrategyDialog extends Dialog<EpisodesObtainStrategy> {
		
		private static final Translation translation;
		
		static {
			String path = "plugin." + PLUGIN.getContext().getPlugin().instance().name() + ".dialog.obtain_episodes";
			translation = MediaDownloader.translation().getTranslation(path);
		}
		
		private final Program program;
		private final int minPage;
		private final int maxPage;
		private final Document firstDocument;
		
		private final VBox boxOptions;
		private final ToggleGroup toggleGroup;
		private final RadioButton optionDateRange;
		private final RadioButton optionPageRange;
		private final RadioButton optionAll;
		
		private final DatePicker pickerDateFrom;
		private final DatePicker pickerDateTo;
		private final IntegerTextField txtPageRangeFrom;
		private final IntegerTextField txtPageRangeTo;
		
		public EpisodesObtainStrategyDialog(Program program, int minPage, int maxPage, Document firstDocument) {
			this.program = Objects.requireNonNull(program);
			this.minPage = minPage;
			this.maxPage = maxPage;
			this.firstDocument = firstDocument;
			
			final DialogPane pane = getDialogPane();
			boxOptions = new VBox(5.0);
			toggleGroup = new ToggleGroup();
			optionDateRange = new RadioButton(translation.getSingle("option.date_range.label"));
			optionPageRange = new RadioButton(translation.getSingle("option.page_range.label"));
			optionAll = new RadioButton(translation.getSingle("option.all.label"));
			
			LocalDate dateNow = LocalDate.now();
			LocalDate dateLastWeek = dateNow.plusWeeks(-1L);
			Label lblDateRangeFrom = new Label(translation.getSingle("option.date_range.from"));
			Label lblDateRangeTo = new Label(translation.getSingle("option.date_range.to"));
			pickerDateFrom = new DatePicker(dateLastWeek);
			pickerDateTo = new DatePicker(dateNow);
			HBox boxDateRange = new HBox(5.0);
			boxDateRange.getChildren().addAll(lblDateRangeFrom, pickerDateFrom, lblDateRangeTo, pickerDateTo);
			boxDateRange.setAlignment(Pos.CENTER_LEFT);
			pickerDateFrom.getEditor().textProperty().addListener((o, ov, text) -> updateDatePicker(pickerDateFrom, text));
			pickerDateTo  .getEditor().textProperty().addListener((o, ov, text) -> updateDatePicker(pickerDateTo,   text));
			
			Label lblPageRangeFrom = new Label(translation.getSingle("option.page_range.from"));
			Label lblPageRangeTo = new Label(translation.getSingle("option.page_range.to"));
			txtPageRangeFrom = new IntegerTextField(minPage);
			txtPageRangeTo = new IntegerTextField(Math.min(minPage + 5, maxPage));
			HBox boxPageRange = new HBox(5.0);
			boxPageRange.getChildren().addAll(lblPageRangeFrom, txtPageRangeFrom, lblPageRangeTo, txtPageRangeTo);
			boxPageRange.setAlignment(Pos.CENTER_LEFT);
			
			optionDateRange.setToggleGroup(toggleGroup);
			optionPageRange.setToggleGroup(toggleGroup);
			optionAll.setToggleGroup(toggleGroup);
			boxOptions.getChildren().addAll(optionDateRange, boxDateRange, optionPageRange, boxPageRange, optionAll);
			pane.setContent(boxOptions);
			
			toggleGroup.selectedToggleProperty().addListener((o, ov, toggle) -> {
				if(toggle == optionDateRange) {
					boxDateRange.setDisable(false);
					boxPageRange.setDisable(true);
				} else if(toggle == optionPageRange) {
					boxDateRange.setDisable(true);
					boxPageRange.setDisable(false);
				} else if(toggle == optionAll) {
					boxDateRange.setDisable(true);
					boxPageRange.setDisable(true);
				}
			});
			
			setResultConverter((buttonType) -> {
				if(buttonType == ButtonType.OK) {
					Toggle toggle = toggleGroup.getSelectedToggle();
					if(toggle == optionDateRange) return createDateRangeStrategy();
					if(toggle == optionPageRange) return createPageRangeStrategy();
					if(toggle == optionAll)       return createAllStrategy();
				}
				return null;
			});
			
			setTitle(translation.getSingle("title"));
			setHeaderText(translation.getSingle("description", "page_count", maxPage));
			setContentText(null);
			FXUtils.setDialogIcon(this, MediaDownloader.ICON);
			pane.setMaxWidth(450.0);
			pane.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
			
			// By default select only a date range
			optionDateRange.setSelected(true);
			
			// Normalize the font size of the header text, since it is displayed larger than it should be.
			// It must be done after setting the header text (setHeaderText(text) call).
			Label lblHeaderText = (Label) pane.lookup(".header-panel > .label");
			lblHeaderText.setFont(Font.font(lblHeaderText.getFont().getSize() * 0.96));
			
			// Fix issue with overlaying the content with the buttons bar
			FXUtils.onDialogShow(this, () -> {
				double buttonsHeight = pane.lookup(".button-bar").prefHeight(-1.0);
				Insets insets = boxOptions.getInsets();
				boxOptions.setPadding(new Insets(insets.getTop(), insets.getRight(),
					insets.getBottom() + buttonsHeight * 0.5, insets.getLeft()));
				pane.getScene().getWindow().sizeToScene();
			});
		}
		
		private final void updateDatePicker(DatePicker picker, String text) {
			LocalDate parsedDate;
			if((parsedDate = Ignore.call(() -> picker.getConverter().fromString(text))) != null) {
				// If successfully parsed, set the date as the picker's value
				picker.setValue(parsedDate);
			}
		}
		
		private final EpisodesObtainStrategy createDateRangeStrategy() {
			LocalDate dateFrom = pickerDateFrom.getValue();
			LocalDate dateTo = pickerDateTo.getValue();
			return new DateRangeEpisodesObtainStrategy(program, dateFrom, dateTo);
		}
		
		private final EpisodesObtainStrategy createPageRangeStrategy() {
			int pageFrom = txtPageRangeFrom.getValue();
			int pageTo = txtPageRangeTo.getValue();
			
			Document document = null;
			if(pageFrom == 1) {
				document = firstDocument;
				++pageFrom;
			}
			
			return new PageRangeEpisodesObtainStrategy(program, pageFrom, pageTo, document);
		}
		
		private final EpisodesObtainStrategy createAllStrategy() {
			return new PageRangeEpisodesObtainStrategy(program, minPage + 1, maxPage, firstDocument);
		}
	}
	
	private static final class Authenticator {
		
		private static final String URL_LOGIN = "https://www.barrandov.tv/form/form-prihlaseni.php";
		private static final String URL_REFERER = "https://www.barrandov.tv/prihlaseni.php";
		private static final String URL_REDIRECT = "https://www.barrandov.tv/";
		
		// Forbid anyone to create an instance of this class
		private Authenticator() {
		}
		
		private static final String credentialsName() {
			return "plugin/" + PLUGIN.getContext().getPlugin().instance().name().replace('.', '/');
		}
		
		private static final UsernameCredentials credentials() throws IOException {
			return (UsernameCredentials) CredentialsManager.instance().get(credentialsName());
		}
		
		private static final String valueOrElse(Function<UsernameCredentials, String> getter, String orElse) {
			return Ignore.defaultValue(
				() -> Opt.of(getter.apply(credentials()))
							.ifFalse(String::isBlank)
							.orElse(orElse),
				orElse
			);
		}
		
		public static final String username() {
			return valueOrElse(UsernameCredentials::username, "");
		}
		
		public static final String password() {
			return valueOrElse(UsernameCredentials::password, "");
		}
		
		public static final boolean areLoginDetailsPresent() {
			return !username().isEmpty() && !password().isEmpty();
		}
		
		public static final boolean authenticate() throws Exception {
			return authenticate(username(), password());
		}
		
		public static final boolean authenticate(String username, String password) throws Exception {
			if(username.isEmpty() || password.isEmpty())
				return false;
			
			HttpHeaders headers = Web.Headers.ofSingle(
	 			"Referer", URL_REFERER
	 		);
			
			Map<String, Object> args = Map.of(
	            "presmerovani", URL_REDIRECT,
	            "login", username,
	            "heslo", password,
	            "prihlasit", ""
	        );
			
			String body = Net.queryString(args);
			Response.OfString response = Web.request(Request.of(Net.uri(URL_LOGIN)).headers(headers).POST(body));
			return response.uri().toString().equals(URL_REDIRECT);
		}
	}
	
	private static final class JSONUtils {
		
		private static Regex REGEX_SINGLELINE_COMMENT;
		private static Regex REGEX_MULTILINE_COMMENT;
		
		private static final Regex regexSingleLineComments() {
			if(REGEX_SINGLELINE_COMMENT == null) {
				REGEX_SINGLELINE_COMMENT = Regex.of("(?m)^(.*(?:\"[^\"\\/\\n\\r]*\\/\\/[^\"\\/\\n\\r]*\")*)\\s*\\/\\/.*$");
			}
			return REGEX_SINGLELINE_COMMENT;
		}
		
		private static final Regex regexMultiLineComments() {
			if(REGEX_MULTILINE_COMMENT == null) {
				REGEX_MULTILINE_COMMENT = Regex.of("(?s)^(.*(?:\"[^\"\\/\\*\\n\\r]*\\/\\*[^\"\\/\\*\\n\\r]*\")*)\\/\\*(?!\\/\\*).*\\*\\/\\s*");
			}
			return REGEX_MULTILINE_COMMENT;
		}
		
		public static final String removeSingleLineComments(String content) {
			return regexSingleLineComments().matcher(content).replaceAll("$1");
		}
		
		public static final String removeMultiLineComments(String content) {
			return regexMultiLineComments().matcher(content).replaceAll("$1");
		}
		
		public static final String removeComments(String content) {
			return removeMultiLineComments(removeSingleLineComments(content));
		}
	}
}