package im.arun.pageindex.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides a shared bounded thread pool for all concurrent operations.
 * Prevents unbounded thread creation from CompletableFuture.supplyAsync()
 * using the default ForkJoinPool.
 */
public final class ExecutorProvider {
    private static volatile ExecutorService instance;
    private static final Object LOCK = new Object();

    private ExecutorProvider() {}

    /**
     * Returns a shared ExecutorService sized for I/O-bound workloads (LLM API calls).
     * Uses a fixed thread pool with size = availableProcessors * 4, capped at 32,
     * to allow high concurrency for network-bound tasks without overwhelming the system.
     */
    public static ExecutorService getExecutor() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    int poolSize = Math.min(Runtime.getRuntime().availableProcessors() * 4, 32);
                    instance = Executors.newFixedThreadPool(poolSize, new ThreadFactory() {
                        private final AtomicInteger counter = new AtomicInteger(0);
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "pageindex-worker-" + counter.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    });
                }
            }
        }
        return instance;
    }

    /**
     * Shuts down the shared executor. Call this during application shutdown.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (instance != null) {
                instance.shutdown();
                instance = null;
            }
        }
    }
}
