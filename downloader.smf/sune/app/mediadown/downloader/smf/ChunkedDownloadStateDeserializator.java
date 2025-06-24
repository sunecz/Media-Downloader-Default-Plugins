package sune.app.mediadown.downloader.smf;

import sune.app.mediadown.pipeline.DownloadPipelineTask;
import sune.app.mediadown.pipeline.PipelineMedia;
import sune.app.mediadown.pipeline.state.PipelineStates.Deserialization;
import sune.app.mediadown.pipeline.state.PipelineStates.DeserializationResult;
import sune.app.mediadown.pipeline.state.PipelineStates.Deserializator;
import sune.app.mediadown.util.JSON.JSONCollection;

// package-private
final class ChunkedDownloadStateDeserializator implements Deserializator {
	
	@Override
	public DeserializationResult deserialize(JSONCollection data) throws Exception {
		PipelineMedia media = Deserialization.pipelineMedia(data.getCollection("input"));
		JSONCollection state = data.getCollection("state");
		
		ChunkedDownloadInitialState initialState = new ChunkedDownloadInitialState(
			state.getInt("resource_index", 0),
			state.getLong("src_position", 0L),
			state.getLong("dst_position", 0L)
		);
		
		return new DeserializationResult(
			media,
			DownloadPipelineTask.of(media, initialState),
			false
		);
	}
}
