package sune.app.mediadown.downloader.wms.common;

import java.util.concurrent.TimeUnit;

import sune.app.mediadown.event.tracker.PipelineProgress;
import sune.app.mediadown.event.tracker.PipelineStates;
import sune.app.mediadown.event.tracker.SimpleTracker;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.util.Utils;

public final class RetryDownloadSimpleTracker
		extends SimpleTracker
		implements DownloadRetryTracker {
	
	private final Translation translation;
	private final int maxRetryAttempts;
	private String progressText;
	private int attempt;
	private long timeMs = -1L;
	private long totalTimeMs;
	
	public RetryDownloadSimpleTracker(int maxRetryAttempts) {
		this.translation = Common.translation();
		this.maxRetryAttempts = maxRetryAttempts;
	}
	
	private final void updateText() {
		String progressText;
		
		if(timeMs >= 0L) {
			String format = translation.getSingle("progress.retry_attempt_wait");
			progressText = Utils.format(
				format,
				"attempt", attempt,
				"total_attempts", maxRetryAttempts,
				"time", Utils.OfFormat.time(timeMs, TimeUnit.MILLISECONDS, true),
				"total_time", Utils.OfFormat.time(totalTimeMs, TimeUnit.MILLISECONDS, true)
			);
		} else {
			String format = translation.getSingle("progress.retry_attempt");
			progressText = Utils.format(
				format,
				"attempt", attempt,
				"total_attempts", maxRetryAttempts
			);
		}
		
		this.progressText = progressText;
		update();
	}
	
	@Override
	public void attempt(int attempt) {
		this.attempt = attempt;
		updateText();
	}
	
	@Override
	public void timeMs(long timeMs) {
		this.timeMs = timeMs;
		updateText();
	}
	
	@Override
	public void totalTimeMs(long totalTimeMs) {
		this.totalTimeMs = totalTimeMs;
		updateText();
	}
	
	@Override
	public String state() {
		return PipelineStates.RETRY;
	}
	
	@Override
	public double progress() {
		return PipelineProgress.PROCESSING;
	}
	
	@Override
	public String textProgress() {
		return progressText;
	}
}