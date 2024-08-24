package sune.app.mediadown.downloader.wms.common;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import sune.app.mediadown.MediaDownloader;
import sune.app.mediadown.language.Translation;
import sune.app.mediadown.net.Web;

public final class Common {
	
	private static final int DEFAULT_BUFFER_SIZE = 8192;
	private static final int DEFAULT_FILE_STORE_BLOCKS_COUNT = 16;
	
	public static final HttpHeaders HEADERS = Web.Headers.ofSingle("Accept", "*/*");
	
	// Forbid anyone to create an instance of this class
	private Common() {
	}
	
	public static final Translation translation() {
		return MediaDownloader.translation().getTranslation("plugin.downloader.wms");
	}
	
	public static final int bufferSize(Path path) {
		return bufferSize(path, DEFAULT_FILE_STORE_BLOCKS_COUNT);
	}
	
	public static final int bufferSize(Path path, int numOfBlocks) {
		try {
			return (int) (numOfBlocks * Files.getFileStore(path).getBlockSize());
		} catch(IOException ex) {
			// Ignore
		}
		
		return DEFAULT_BUFFER_SIZE;
	}
	
	public static final ByteBuffer newDirectBuffer(Path path) {
		return newDirectBuffer(path, DEFAULT_FILE_STORE_BLOCKS_COUNT);
	}
	
	public static final ByteBuffer newDirectBuffer(Path path, int numOfBlocks) {
		return ByteBuffer.allocateDirect(bufferSize(path, numOfBlocks));
	}
}