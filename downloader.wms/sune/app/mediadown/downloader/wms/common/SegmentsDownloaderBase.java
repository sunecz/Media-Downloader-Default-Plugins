package sune.app.mediadown.downloader.wms.common;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import sune.app.mediadown.download.DownloadConfiguration;
import sune.app.mediadown.download.InternalDownloader;
import sune.app.mediadown.event.Event;
import sune.app.mediadown.event.EventRegistry;
import sune.app.mediadown.event.Listener;
import sune.app.mediadown.event.tracker.TrackerEvent;
import sune.app.mediadown.exception.RejectedResponseException;
import sune.app.mediadown.net.Web.Request;
import sune.app.mediadown.util.Range;

public abstract class SegmentsDownloaderBase implements InternalSegmentsDownloader {
	
	protected final DownloaderState state;
	protected final InternalDownloader downloader;
	protected final DownloadConfiguration.Builder configurationBuilder;
	protected final EventRegistry<TrackerEvent> eventRegistry;
	
	protected long written;
	
	protected SegmentsDownloaderBase(
		DownloaderState state,
		DownloadConfiguration.Builder configurationBuilder,
		InternalDownloader downloader
	) {
		this.state = Objects.requireNonNull(state);
		this.downloader = Objects.requireNonNull(downloader);
		this.configurationBuilder = Objects.requireNonNull(configurationBuilder);
		this.eventRegistry = new EventRegistry<>();
	}
	
	protected abstract boolean doRetry(int attempt);
	
	protected boolean downloadSegment(RemoteFile segment, Path output) throws Exception {
		if(!state.check()) return false;
		
		final int maxRetryAttempts = state.maxRetryAttempts();
		Request request = Request.of(segment.uri()).headers(Common.HEADERS).GET();
		boolean lastAttempt = false;
		boolean error = false;
		Exception exception = null;
		long downloadedBytes = 0L;
		
		for(int i = 0; (error || downloadedBytes <= 0L) && i <= maxRetryAttempts; ++i) {
			if(!state.check()) return false;
			
			lastAttempt = i == maxRetryAttempts;
			state.setPropagateError(lastAttempt);
			error = false;
			exception = null;
			
			// Only display the text, if we're actually retrying
			if(i > 0) {
				if(!doRetry(i)) {
					return false;
				}
			}
			
			try {
				downloadedBytes = downloader.start(
					request,
					output,
					configurationBuilder
						.totalBytes(segment.size())
						.rangeOutput(new Range<>(written, -1L))
						.build()
				);
				error = downloader.isError() || downloadedBytes < 0L;
			} catch(InterruptedException ex) {
				// When stopped, immediately break from the loop
				return false;
			} catch(RejectedResponseException ex) {
				// Retry, if the response is rejected by the filter
				error = true;
			} catch(Exception ex) {
				error = true;
				exception = ex;
			}
			
			if(!error) {
				long size = segment.size();
				
				// Check whether the size is already available. If not, get it from
				// the downloader itself. This should fix an issue when just a part
				// of the whole segment was downloaded and the size couldn't be checked,
				// thus the file became incomplete/corrupted.
				if(size < 0L) {
					size = downloader.totalBytes();
				}
				
				if(size >= 0L) {
					state.alterTotalSize(size - segment.estimatedSize());
					segment.size(size);
					
					// Check whether the downloaded size equals the total size, if not
					// just retry the download again.
					if(downloadedBytes != size) {
						error = true;
					}
				}
			}
			
			if(error) {
				if(downloadedBytes > 0L) {
					state.alterDownloadedSize(-downloadedBytes);
				}
				
				downloadedBytes = -1L;
			}
		}
		
		// If even the last attempt failed, throw an exception since there is nothing we can do.
		if(lastAttempt && downloadedBytes <= 0L) {
			exception = new IllegalStateException("The last attempt failed");
		}
		
		// Allow error propagating since there will be no more retry attempts
		state.setPropagateError(true);
		
		if(exception != null) {
			throw exception; // Forward the exception
		}
		
		long bytes;
		if((bytes = downloader.writtenBytes()) > 0L) {
			written += bytes;
		}
		
		return true;
	}
	
	protected boolean download(
		List<? extends RemoteFile> segments,
		Path output
	) throws Exception {
		written = 0L;
		
		for(RemoteFile segment : segments) {
			if(!downloadSegment(segment, output)) {
				return false;
			}
		}
		
		return true;
	}
	
	@Override
	public boolean download(Segments segments) throws Exception {
		return download(segments.segments(), segments.output());
	}
	
	@Override
	public void close() throws Exception {
		InternalDownloader downloader;
		if((downloader = this.downloader) != null) {
			downloader.close();
		}
	}
	
	@Override
	public <V> void addEventListener(
		Event<? extends TrackerEvent, V> event,
		Listener<V> listener
	) {
		eventRegistry.add(event, listener);
	}
	
	@Override
	public <V> void removeEventListener(
		Event<? extends TrackerEvent, V> event,
		Listener<V> listener
	) {
		eventRegistry.remove(event, listener);
	}
}