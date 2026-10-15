package com.flow.adhd;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Database(
    entities = {AppState.class, PendingEvent.class},
    version = 1,
    exportSchema = false
)
public abstract class FlowDatabase extends RoomDatabase {

    /** A process-wide FIFO lane for Room calls and ordered native commands. */
    private static final ThreadLocal<Boolean> STORAGE_THREAD = new ThreadLocal<>();
    private static final ExecutorService STORAGE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            STORAGE_THREAD.set(Boolean.TRUE);
            try { runnable.run(); }
            finally { STORAGE_THREAD.remove(); }
        }, "flow-storage");
        thread.setDaemon(true);
        return thread;
    });

    public abstract FlowDao flowDao();

    private static volatile FlowDatabase INSTANCE;

    public static FlowDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (FlowDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            FlowDatabase.class,
                            "flow_db"
                    )
                    .build();
                }
            }
        }
        return INSTANCE;
    }

    static boolean isStorageThread() {
        return Boolean.TRUE.equals(STORAGE_THREAD.get());
    }

    /** Queue asynchronous work in the same order as every other storage user. */
    static void executeStorage(Runnable work) {
        if (work != null) STORAGE_EXECUTOR.execute(work);
    }

    /** Synchronous bridge call, executed on the FIFO lane; safe when reentrant. */
    static <T> T callStorage(Callable<T> work) {
        if (work == null) throw new IllegalArgumentException("work is required");
        if (isStorageThread()) return callInline(work);
        Future<T> future = STORAGE_EXECUTOR.submit(work);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for FLOW storage", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("FLOW storage operation failed", cause);
        }
    }

    private static <T> T callInline(Callable<T> work) {
        try {
            return work.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("FLOW storage operation failed", e);
        }
    }
}
