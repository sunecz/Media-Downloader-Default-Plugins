package sune.app.mediadown.downloader.wms.parallel;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sune.app.mediadown.concurrent.Threads;
import sune.app.mediadown.util.NIO;

// package-private
final class Merger implements AutoCloseable {
	
	private static final OpenOption[] OPEN_OPTIONS = {
		StandardOpenOption.WRITE,
		StandardOpenOption.CREATE,
	};
	
	private final FileChannel outputChannel;
	private final Queue<MergeRequest> requests = new PriorityQueue<>();
	private final Lock lock = new ReentrantLock();
	private final Condition newRequest = lock.newCondition();
	private final Condition hasProcessed = lock.newCondition();
	
	private volatile boolean isRunning;
	private volatile boolean isTerminating;
	private Thread thread;
	private Exception exception;
	
	private int lastNextIndex;
	private MergeRequest head;
	
	// Merger exists in the context of the parallel downloader and thus its workers,
	// so there is no need to use weak-version of the map.
	private final Map<DownloadWorker, DownloadWorkerState> workerStates = new ConcurrentHashMap<>();
	
	public Merger(Path path) throws IOException {
		Objects.requireNonNull(path);
		this.outputChannel = FileChannel.open(path, OPEN_OPTIONS);
	}
	
	private final DownloadWorkerState workerState(DownloadWorker worker) {
		return workerStates.computeIfAbsent(worker, DownloadWorkerState::new);
	}
	
	private final void merge(MergeRequest request) throws Exception {
		DownloadWorker worker = request.worker;
		worker.acquire();
		
		try {
			DownloadWorkerState workerState = workerState(worker);
			workerState.cullOffsets(request.epoch);
			
			FileChannel inputChannel = workerState.channel();
			final long offset = request.offset - workerState.offset(request.epoch);
			final long size = request.size;
			NIO.transferTo(inputChannel, offset, size, outputChannel);
			
			worker.notifyOfMerge(offset, size);
		} finally {
			worker.release();
		}
	}
	
	private final MergeRequest nextRequest(int nextIndex) {
		lock.lock();
		
		try {
			MergeRequest request;
			if((request = requests.peek()) == null
					|| request.index != nextIndex) {
				return null; // No next entry will be suitable
			}
			
			requests.poll(); // Remove the request
			head = requests.peek(); // Update the queue head info
			return request;
		} finally {
			lock.unlock();
		}
	}
	
	private final MergeRequest processRequests() throws Exception {
		int nextIndex = lastNextIndex;
		MergeRequest request;
		
		// Entries are in the ascending order by the index
		for(; (request = nextRequest(nextIndex)) != null; ++nextIndex) {
			// Request is valid and can be merged as the next one
			merge(request);
		}
		
		if(nextIndex == lastNextIndex) {
			return request; // Nothing was merged
		}
		
		lastNextIndex = nextIndex;
		lock.lock();
		
		try {
			// Use signalAll() here so that when a different thread is also waiting
			// in the await() method, it is awoken, too.
			hasProcessed.signalAll();
		} finally {
			lock.unlock();
		}
		
		return request;
	}
	
	private final void done() {
		isTerminating = false;
		isRunning = false;
		lock.lock();
		
		try {
			// Use signalAll() here so that when a different thread is also waiting
			// in the await() method, it is awoken, too.
			hasProcessed.signalAll();
		} finally {
			lock.unlock();
		}
	}
	
	private final void loop() {
		try {
			for(MergeRequest lastHead = null; isRunning;) {
				lock.lock();
				
				try {
					while(head == lastHead
							&& isRunning
							&& (!isTerminating || head != null)) {
						newRequest.await();
					}
				} catch(InterruptedException ex) {
					break; // Interrupted, do not continue
				} finally {
					lock.unlock();
				}
				
				if(!isRunning || (isTerminating && head == null)) {
					break; // Stopped
				}
				
				try {
					lastHead = processRequests();
				} catch(InterruptedException ex) {
					break; // Interrupted, do not continue
				} catch(Exception ex) {
					exception = ex;
					break; // Error, do not continue
				}
			}
		} finally {
			done();
		}
	}
	
	// Must be run in the worker-acquired state.
	// package-private
	void notifyOfCompact(DownloadWorker worker, int epoch, long size) {
		workerState(worker).addOffset(epoch, size);
	}
	
	public void requestMerge(MergeRequest request) {
		Objects.requireNonNull(request);
		lock.lock();
		
		try {
			requests.add(request);
			head = requests.peek();
			newRequest.signal();
		} finally {
			lock.unlock();
		}
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
		lock.lock();
		
		try {
			// Unlock the wait loop
			newRequest.signal();
			// Use signalAll() here so that when a different thread is also waiting
			// in the await() method, it is awoken, too.
			hasProcessed.signalAll();
		} finally {
			lock.unlock();
		}
	}
	
	public void terminate() {
		isTerminating = true;
		lock.lock();
		
		try {
			// Unlock the wait loop
			newRequest.signal();
		} finally {
			lock.unlock();
		}
	}
	
	public void await() throws Exception {
		if(!isRunning) {
			return; // Nothing to await for
		}
		
		lock.lock();
		
		try {
			while(head != null && isRunning) {
				hasProcessed.await();
			}
		} finally {
			lock.unlock();
		}
		
		Exception ex;
		if((ex = exception) != null) {
			throw ex; // Rethrow
		}
	}
	
	@Override
	public void close() throws Exception {
		outputChannel.close();
	}
	
	private static final class DownloadWorkerState {
		
		private final DownloadWorker worker;
		private final Map<Integer, Long> epochOffsets = new HashMap<>();
		
		public DownloadWorkerState(DownloadWorker worker) {
			this.worker = Objects.requireNonNull(worker);
		}
		
		public void addOffset(int epoch, long offset) {
			epochOffsets.compute(epoch, (k, v) -> (v != null ? v : 0L) + offset);
		}
		
		public void cullOffsets(int currentEpoch) {
			epochOffsets.keySet().removeIf((e) -> e < currentEpoch);
		}
		
		public long offset(int epoch) {
			return epochOffsets.getOrDefault(epoch, 0L);
		}
		
		public FileChannel channel() throws IOException {
			return worker.channel();
		}
	}
}