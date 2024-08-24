package sune.app.mediadown.downloader.wms.parallel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.downloader.wms.common.RemoteFileSegment;

// package-private
final class DownloadWorker implements AutoCloseable {
	
	private static final long COMPACT_MIN_SEGMENT_SIZE = 1024L * 1024L; // 1 MB
	
	private final Synchronizer synchronizer;
	private final DownloadQueue queue;
	private final Merger merger;
	private final SerialDownloader downloader;
	private final Path path;
	
	private final Lock lock = new ReentrantLock();
	private final Condition isDone = lock.newCondition();
	private final long compactThresholdBytes;
	
	private volatile long mergeFence;
	private long downloadOffset;
	
	private FileCompactor compactor;
	private int compactEpoch;
	
	private volatile boolean isRunning;
	private Thread thread;
	private Exception exception;
	
	public DownloadWorker(
		Synchronizer synchronizer,
		DownloadQueue queue,
		Merger merger,
		SerialDownloader downloader,
		Path path
	) {
		this.synchronizer = Objects.requireNonNull(synchronizer);
		this.queue = Objects.requireNonNull(queue);
		this.merger = Objects.requireNonNull(merger);
		this.downloader = Objects.requireNonNull(downloader);
		this.path = Objects.requireNonNull(path);
		this.compactThresholdBytes = calculateCompactThresholdBytes(queue);
	}
	
	private static final long calculateCompactThresholdBytes(DownloadQueue queue) {
		return Math.max(queue.averageSize(), COMPACT_MIN_SEGMENT_SIZE) * 16L;
	}
	
	static int compactCount = 0;
	static final Object compactSync = new Object();
	
	private final void compact(long size, long totalSize) throws IOException {
		FileCompactor compactor;
		if((compactor = this.compactor) == null) {
			compactor = new FileCompactor(path);
			this.compactor = compactor;
		}
		
		compactor.compact(size, totalSize);
		merger.notifyOfCompact(this, compactEpoch, size);
	}
	
	private final void tryCompact() throws IOException {
		long fence;
		if((fence = mergeFence) <= 0L) {
			return; // Nothing to compact
		}
		
		long totalSize;
		if((totalSize = downloader.written()) < compactThresholdBytes) {
			return; // Not enough data
		}
		
		lock.lock();
		try {
			compact(fence, totalSize);
			++compactEpoch;
			downloader.addWritten(-fence);
			mergeFence = 0L;
		} finally {
			lock.unlock();
		}
	}
	
	private final void requestMerge(int index, long offset, long size) {
		merger.requestMerge(new MergeRequest(this, index, offset, size, compactEpoch));
	}
	
	private final boolean doDownloadSegment(RemoteFileSegment segment) throws Exception {
		downloadOffset = downloader.written(); // Remember for the merge request
		return downloader.downloadSegment(segment, path);
	}
	
	private final void requestMerge(RemoteFileSegment segment) {
		requestMerge(segment.index(), downloadOffset, segment.size());
	}
	
	private final boolean downloadSegment(RemoteFileSegment segment) throws Exception {
		tryCompact();
		
		if(!doDownloadSegment(segment)) {
			return false;
		}
		
		requestMerge(segment);
		return true;
	}
	
	private final boolean downloadNext() throws Exception {
		RemoteFileSegment segment;
		return queue.hasNext()
					&& (segment = queue.next()) != null
					&& downloadSegment(segment);
	}
	
	private final void done() {
		isRunning = false;
		lock.lock();
		
		try {
			// Use signalAll() here so that when a different thread is also waiting
			// in the await() method, it is awoken, too.
			isDone.signalAll();
		} finally {
			lock.unlock();
			synchronizer.unlock(this, exception);
		}
	}
	
	private final void loop() {
		try {
			while(isRunning) {
				try {
					if(!downloadNext()) {
						break; // Interrupted or queue is empty, do not continue
					}
				} catch(Exception ex) {
					exception = ex;
					break; // Error, do not continue
				}
			}
		} finally {
			done();
		}
	}
	
	// package-private
	final void acquire() {
		lock.lock();
	}
	
	// package-private
	final void release() {
		lock.unlock();
	}
	
	// package-private
	final void notifyOfMerge(long offset, long size) {
		long fence;
		if((fence = mergeFence) <= 0L) {
			fence = 0L;
		}
		
		mergeFence = fence + size;
	}
	
	public void start() {
		if(isRunning) {
			return; // Already running
		}
		
		isRunning = true;
		thread = Threads.newThreadUnmanaged(this::loop);
		thread.start();
	}
	
	public void stop() {
		if(!isRunning) {
			return; // Not running
		}
		
		isRunning = false;
	}
	
	public void await() throws InterruptedException {
		if(!isRunning) {
			return; // Nothing to await for
		}
		
		lock.lock();
		
		try {
			while(isRunning) {
				isDone.await();
			}
		} finally {
			lock.unlock();
		}
	}
	
	@Override
	public void close() throws Exception {
		downloader.close();
	}
	
	public Path output() {
		return path;
	}
}