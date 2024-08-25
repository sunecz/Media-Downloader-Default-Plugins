package sune.app.mediadown.downloader.wms.parallel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

// package-private
final class FileCompactor {
	
	private final FileChannel channel;
	private final ByteBuffer buf;
	
	public FileCompactor(FileChannel channel, int bufferSize) throws IOException {
		this.channel = Objects.requireNonNull(channel);
		this.buf = ByteBuffer.allocateDirect(bufferSize);
	}
	
	public void compact(long size, long totalSize) throws IOException {
		final long cap = buf.capacity();
		long rem = totalSize - size;
		long rd = size, wr = 0L;
		
		// On MacOS we can't transfer the bytes into the same file, if the regions that
		// are being transferred from/to, overlap. Therefore, use a different strategy,
		// i.e. do not use Files::transferFrom/To methods.
		// See: https://bugs.openjdk.org/browse/JDK-8140241
		
		for(int v, n, num; rem > 0L; rem -= num) {
			num = (int) Math.min(cap, rem);
			
			// Read the bytes to the buffer from the channel
			for(n = num; n > 0 && (v = channel.read(buf, rd)) >= 0; rd += v, n -= v);
			buf.flip();
			
			// Write the bytes from the buffer to the channel
			for(n = num; n > 0 && (v = channel.write(buf, wr)) >= 0; wr += v, n -= v);
			buf.clear();
		}
	}
}