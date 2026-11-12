package app.aster.browser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Serializes writes, keeping only the latest pending snapshot for each key. */
final class CoalescingExecutor {
    private final Executor executor;
    private final Map<String, Runnable> pending = new LinkedHashMap<>();
    private boolean running;

    CoalescingExecutor(Executor executor) {
        this.executor = executor;
    }

    synchronized void submit(String key, Runnable task) {
        pending.put(key, task);
        if (!running) {
            running = true;
            executor.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            Runnable task;
            synchronized (this) {
                if (pending.isEmpty()) {
                    running = false;
                    return;
                }
                String key = pending.keySet().iterator().next();
                task = pending.remove(key);
            }
            // Each caller handles its own storage error. Always continue draining
            // other keys if an unexpected exception escapes a write.
            try {
                task.run();
            } catch (RuntimeException ignored) {
                // A failed write must not permanently stop later persistence.
            }
        }
    }
}
