package sune.app.mediadown.downloader.smf;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.entity.Downloaders;
import sune.app.mediadown.pipeline.state.MetricsComparator;
import sune.app.mediadown.pipeline.state.PipelineStates;
import sune.app.mediadown.plugin.Plugin;
import sune.app.mediadown.plugin.PluginBase;

@Plugin(name          = "downloader.smf",
	    title         = "plugin.downloader.smf.title",
	    version       = "00.02.09-0008",
	    author        = "Sune",
	    updateBaseURL = "https://app.sune.tech/mediadown/dat/plugin/0002/downloader/smf/",
	    updatable     = true)
public final class SMFDownloaderPlugin extends PluginBase {
	
	private static final String NAME = "smf";
	private String translatedTitle;
	
	private final void initDownload() {
		MetricsComparator.Registry.register(ChunkedDownloadState.metricsComparator());
		PipelineStates.register("download", new ChunkedDownloadStateDeserializator());
	}
	
	@Override
	public void init() throws Exception {
		translatedTitle = MediaDownloader.translation().getSingle(super.getTitle());
		Downloaders.add(NAME, SMFDownloader.class);
		initDownload();
	}
	
	@Override
	public void dispose() throws Exception {
		// Do nothing
	}
	
	@Override
	public String getTitle() {
		return translatedTitle;
	}
}