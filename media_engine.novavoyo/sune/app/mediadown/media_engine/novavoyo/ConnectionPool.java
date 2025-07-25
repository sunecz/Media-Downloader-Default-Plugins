package sune.app.mediadown.media_engine.novavoyo;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import sune.app.mediadown.media_engine.novavoyo.Authenticator.AuthenticationToken;

public final class ConnectionPool implements AutoCloseable {
	
	private static final long AUTOCLOSE_ITEM_AFTER_MS = 20000L;
	
	private final Device device;
	private final ConnectionItem[] pool;
	private final boolean[] acquired;
	private final Lock lock = new ReentrantLock();
	private final Condition isAvailable = lock.newCondition();
	private final ScheduledExecutorService scheduler;
	private final ScheduledFuture<?>[] scheduleFutures;
	private volatile boolean isActive = true;
	private AuthenticationToken authToken;
	
	public ConnectionPool(int capacity, Device device) {
		this.device = Objects.requireNonNull(device);
		
		pool = new ConnectionItem[capacity];
		acquired = new boolean[capacity];
		
		for(int i = 0, l = capacity; i < l; ++i) {
			pool[i] = new ConnectionItem(i);
		}
		
		scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
		scheduleFutures = new ScheduledFuture[capacity];
	}
	
	private final ConnectionItem acquire() throws InterruptedException {
		if(!isActive) {
			throw new IllegalStateException("Not active");
		}
		
		lock.lock();
		
		try {
			while(true) {
				for(int i = 0, l = acquired.length; i < l; ++i) {
					if(!acquired[i]) {
						ConnectionItem item = pool[i];
						item.acquire(); // Ensure the item is initialized internally
						acquired[i] = true;
						return item;
					}
				}
				
				isAvailable.await();
				
				if(!isActive) {
					throw new InterruptedException();
				}
			}
		} finally {
			lock.unlock();
		}
	}
	
	private final void itemReleased(ConnectionItem item) {
		if(!isActive) {
			return;
		}
		
		lock.lock();
		
		try {
			acquired[item.idx] = false;
			isAvailable.signal();
		} finally {
			lock.unlock();
		}
	}
	
	private final void releaseAll() {
		isActive = false;
		
		lock.lock();
		
		for(int i = 0, l = acquired.length; i < l; ++i) {
			if(acquired[i]) {
				pool[i].release();
			}
		}
		
		try {
			Arrays.fill(acquired, false);
			Arrays.fill(pool, null);
			isAvailable.signalAll();
		} finally {
			lock.unlock();
		}
	}
	
	private final void closeUnusedItem(ConnectionItem item) {
		lock.lock();
		
		try {
			if(acquired[item.idx]) {
				ScheduledFuture<?> future = scheduleItemClose(item, AUTOCLOSE_ITEM_AFTER_MS);
				scheduleFutures[item.idx] = future;
				return; // Currently acquired, cannot close
			}
			
			item.dispose();
		} finally {
			lock.unlock();
		}
	}
	
	private final ScheduledFuture<?> scheduleItemClose(ConnectionItem item, long ms) {
		return scheduler.schedule(() -> closeUnusedItem(item), ms, TimeUnit.MILLISECONDS);
	}
	
	private final void itemAcquired(ConnectionItem item) {
		lock.lock();
		
		try {
			ScheduledFuture<?> future;
			if((future = scheduleFutures[item.idx]) != null) {
				future.cancel(true);
			}
			
			future = scheduleItemClose(item, AUTOCLOSE_ITEM_AFTER_MS);
			scheduleFutures[item.idx] = future;
		} finally {
			lock.unlock();
		}
	}
	
	public void authenticate(AuthenticationToken authToken) {
		this.authToken = authToken;
		
		lock.lock();
		
		try {
			for(ConnectionItem item : pool) {
				Connection connection = item.connection;
				
				if(connection != null) {
					connection.authenticate(authToken);
				}
			}
		} finally {
			lock.unlock();
		}
	}
	
	public boolean isAuthenticated() {
		return authToken != null;
	}
	
	@Override
	public void close() throws Exception {
		lock.lock();
		
		try {
			for(int i = 0, l = scheduleFutures.length; i < l; ++i) {
				ScheduledFuture<?> future = scheduleFutures[i];
				
				if(future != null) {
					future.cancel(true);
				}
			}
			
			scheduler.shutdownNow();
			scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
			Arrays.fill(scheduleFutures, null);
			
			releaseAll();
		} finally {
			lock.unlock();
		}
	}
	
	public ConnectionItem get() throws InterruptedException {
		return acquire();
	}
	
	private static final class DaemonThreadFactory implements ThreadFactory {
		
		@Override
		public Thread newThread(Runnable r) {
			Thread thread = new Thread(r);
			thread.setDaemon(true);
			return thread;
		}
	}
	
	public final class ConnectionItem implements AutoCloseable {
		
		private final int idx;
		private Connection connection;
		
		private ConnectionItem(int idx) {
			this.idx = idx;
		}
		
		private final void acquire() {
			Connection con;
			if((con = this.connection) == null) {
				con = new Connection(Common.newUUID(), device);
				con.open(); // Automatically open
				
				if(authToken != null) {
					con.authenticate(authToken); // Automatically authenticate
				}
				
				this.connection = con;
			}
			
			itemAcquired(this);
		}
		
		private final void release() {
			itemReleased(this);
		}
		
		private final void dispose() {
			release();
			
			Connection con;
			if((con = this.connection) != null) {
				con.close();
				this.connection = null;
			}
		}
		
		@Override
		public void close() throws Exception {
			release();
		}
		
		public int idx() {
			return idx;
		}
		
		public Connection connection() {
			return connection;
		}
	}
}
