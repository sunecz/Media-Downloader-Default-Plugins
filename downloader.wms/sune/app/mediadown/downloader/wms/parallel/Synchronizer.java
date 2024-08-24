package sune.app.mediadown.downloader.wms.parallel;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// package-private
final class Synchronizer {
	
	private final Lock lock = new ReentrantLock();
	private final Condition isEnd = lock.newCondition();
	private int activeCount;
	private Exception exception;
	
	public Synchronizer(int count) {
		if(count <= 0) {
			throw new IllegalArgumentException("Count must be > 0");
		}
		
		this.activeCount = count;
	}
	
	public void unlock(Object object, Exception exception) {
		lock.lock();
		
		try {
			if(--activeCount == 0 || exception != null) {
				this.exception = exception;
				isEnd.signalAll();
			}
		} finally {
			lock.unlock();
		}
	}
	
	public void await() throws Exception {
		lock.lock();
		
		try {
			while(activeCount > 0 && exception == null) {
				isEnd.await();
			}
		} finally {
			lock.unlock();
		}
		
		Exception ex;
		if((ex = exception) != null) {
			throw ex; // Rethrow
		}
	}
}