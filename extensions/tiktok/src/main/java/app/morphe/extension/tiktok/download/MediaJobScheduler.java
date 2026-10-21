/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One bounded queue for every extension-owned media operation. */
final class MediaJobScheduler {
    /** The most saves that run at once. Saves at once can lower it, never raise it. */
    static final int MAX_RUNNING_JOBS = 3;
    static final int MAX_QUEUED_JOBS = 8;

    private static final ThreadFactory THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "Hushfeed-MediaJob");
        thread.setDaemon(true);
        return thread;
    };
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            MAX_RUNNING_JOBS,
            MAX_RUNNING_JOBS,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_JOBS),
            THREAD_FACTORY,
            new ThreadPoolExecutor.AbortPolicy());
    /** Accepted jobs that are not over yet, waiting or running: how far back a new one starts. */
    private static final AtomicInteger ADMITTED = new AtomicInteger();
    /**
     * The live job under each key a saver named, so a second request can say what the first is
     * doing. Typed to the concrete class, not {@link Map}: its two-argument {@code remove} is a
     * plain method on {@link ConcurrentHashMap} since API 1, but a default method on {@code Map}
     * since API 24, and a call resolves by the reference's declared type, not the object's.
     */
    private static final ConcurrentHashMap<String, Job> BY_KEY = new ConcurrentHashMap<>();
    /** Accepted jobs that are not over yet, in the order they were accepted, for the Downloads page (#27). */
    private static final Set<Job> LIVE = Collections.synchronizedSet(new LinkedHashSet<>());
    /** The most ended saves the Downloads page keeps from this session. */
    static final int MAX_ENDED = 5;
    /** Saves that ended this session, newest first, for the Downloads page. Guarded by itself. */
    private static final ArrayDeque<Ended> ENDED = new ArrayDeque<>();
    /** The job this worker thread is running, so what its save tells the reader is kept with it. */
    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();

    static {
        // A save mostly tells the reader how it went with a toast from its worker thread.
        Utils.setToastObserver(MediaJobScheduler::said);
    }

    private MediaJobScheduler() {}

    /**
     * A save that is over, as the Downloads page lists it under the ones still going. Only what
     * that takes, not the job, whose work can hold a whole list of posts.
     */
    static final class Ended {
        final String label;
        /** The last thing the save told the reader as it ran, or null when it said nothing. */
        final String said;
        /** Taken out of line before it started. */
        final boolean cancelled;
        /** Stopped by an error the save didn't expect. */
        final boolean failed;

        Ended(String label, String said, boolean cancelled, boolean failed) {
            this.label = label;
            this.said = said;
            this.cancelled = cancelled;
            this.failed = failed;
        }
    }

    /**
     * One accepted save, from its place in line to its end. It waits, then runs, then is over,
     * or it is cancelled while it still waits. Whichever way it goes, its {@code done} runs
     * exactly once after: that is where a saver lets go of what it held for the save (its id,
     * its busy flag, its button). It says the job is over, not that it worked; the work reports
     * its own result, since a save that returns normally may still have saved nothing.
     */
    static final class Job implements Runnable {
        private static final int WAITING = 0;
        private static final int RUNNING = 1;
        private static final int OVER = 2;

        final String label;
        final String key;
        /** Saves that had to end before this one could start, counted as it was accepted. */
        final int ahead;
        private final Runnable work;
        private final Runnable done;
        /** What this save leaves on disk for the start after a closed TikTok; null when nothing does. */
        final SaveRecords.Record record;
        /** The progress row following this save, for its count and its Cancel once it runs; null for none. */
        volatile SaveProgress progress;
        private final AtomicInteger state = new AtomicInteger(WAITING);
        /** The last thing this save told the reader while it ran. */
        private volatile String said;

        private Job(String label, String key, Runnable work, Runnable done, int ahead,
                SaveRecords.Record record) {
            this.label = label;
            this.key = key;
            this.work = work;
            this.done = done;
            this.ahead = ahead;
            this.record = record;
        }

        /** True until a worker takes it, and false for good once it is cancelled. */
        boolean waiting() {
            return state.get() == WAITING;
        }

        /** True while a worker has it. */
        boolean running() {
            return state.get() == RUNNING;
        }

        /**
         * Takes a job that is still waiting out of the line and ends it. False once a worker has
         * it: a transfer already under way is not stopped from here.
         */
        boolean cancel() {
            if (!state.compareAndSet(WAITING, OVER)) return false;
            EXECUTOR.remove(this);
            remember(new Ended(label, null, true, false));
            end();
            return true;
        }

        @Override public void run() {
            // A cancel that won the race has already ended the job, done included.
            if (!state.compareAndSet(WAITING, RUNNING)) return;
            // A file this save publishes is written down against its record as it lands.
            SaveRecords.Record outer = SaveRecords.enter(record);
            Job outerJob = CURRENT.get();
            CURRENT.set(this);
            boolean failed = false;
            try {
                MediaBudget.runWithJobDeadline(work);
            } catch (RuntimeException failure) {
                // The saver says what went wrong for anything it expects. What it didn't expect
                // must not take TikTok down with the worker thread.
                failed = true;
                Logger.printException(() -> "Media job failed for " + label, failure);
            } finally {
                if (outerJob == null) CURRENT.remove();
                else CURRENT.set(outerJob);
                SaveRecords.exit(outer);
                // Listed as ended before it stops counting as running, so a refresh of the
                // Downloads page between the two still finds it.
                remember(new Ended(label, said, false, failed));
                state.set(OVER);
                end();
            }
        }

        private void end() {
            ADMITTED.decrementAndGet();
            LIVE.remove(this);
            if (key != null) BY_KEY.remove(key, this);
            try {
                if (done != null) done.run();
            } catch (RuntimeException failure) {
                Logger.printException(() -> "Media job cleanup failed for " + label, failure);
            } finally {
                // Over, whichever way: the record closes whether the save worked or not.
                SaveRecords.close(record);
            }
        }
    }

    static boolean submit(String label, Runnable work) {
        return submit(label, null, work, null) != null;
    }

    /**
     * Queues {@code work}, or refuses it when the line is full. A refusal says so and runs
     * {@code done} before returning null, so the caller's cleanup is the same either way.
     *
     * @param key names what is being saved, so {@link #busyMessage} can find it; null for none
     */
    static Job submit(String label, String key, Runnable work, Runnable done) {
        return submit(label, key, 1, work, done);
    }

    /**
     * As above, for a save of {@code files} files, which is what the start after a closed TikTok
     * counts it against when some of them never landed.
     */
    static Job submit(String label, String key, int files, Runnable work, Runnable done) {
        if (work == null) throw new NullPointerException("work");
        int limit = applyLimit();
        int before = ADMITTED.getAndIncrement();
        Job job = new Job(label, key, work, done, Math.max(0, before - limit + 1),
                SaveRecords.open(label, files));
        LIVE.add(job);
        if (key != null) BY_KEY.put(key, job);
        try {
            EXECUTOR.execute(job);
            SaveRecords.accepted(job.record);
            return job;
        } catch (RejectedExecutionException error) {
            // Never listed as ended: it was never accepted. A cancel that got in first ended it.
            if (job.state.compareAndSet(Job.WAITING, Job.OVER)) job.end();
            Logger.printException(() -> "Media job queue is full for " + label, error);
            Utils.showToastLong(L10n.t("Too many media saves are already running. Try again in a moment."));
            return null;
        }
    }

    /**
     * The word an accepted save gets at once, for a save with no progress row of its own.
     * {@code starting} is said when a worker is free, and null says nothing for a save whose
     * own start speaks a moment later. A save that has to wait says {@code waiting} and how
     * many saves are ahead of it, so a quiet minute doesn't read as a tap that did nothing.
     */
    static void acknowledge(Job job, String starting, String waiting) {
        if (job == null) return;
        if (job.ahead == 0) {
            if (starting != null) Utils.showToastShort(starting);
            return;
        }
        String line = L10n.quantity(Utils.getContext(), job.ahead,
                "Starts after one other save", "Starts after %1$s other saves");
        // Two lines, each translated whole: what the save is, then what it waits for.
        String text = waiting == null ? line : waiting + "\n" + line;
        Utils.showToastShort(text);
    }

    /** What a second request for something already accepted is told: what the first is doing. */
    static String busyMessage(String key) {
        Job job = key == null ? null : BY_KEY.get(key);
        return job != null && job.waiting()
                ? L10n.t("The last one is still waiting to start")
                : L10n.t("Still saving the last one");
    }

    /** The live job saved under {@code key}, or null. */
    static Job job(String key) {
        return BY_KEY.get(key);
    }

    /** Every accepted job that is not over yet, oldest first: a copy, so a job ending doesn't change it. */
    static List<Job> jobs() {
        synchronized (LIVE) {
            return new ArrayList<>(LIVE);
        }
    }

    /** Lists a save as ended, newest first, keeping the last {@link #MAX_ENDED}. */
    private static void remember(Ended ended) {
        synchronized (ENDED) {
            ENDED.addFirst(ended);
            while (ENDED.size() > MAX_ENDED) ENDED.removeLast();
        }
    }

    /** The saves that ended this session, newest first: a copy. */
    static List<Ended> ended() {
        synchronized (ENDED) {
            return new ArrayList<>(ENDED);
        }
    }

    static void clearEndedForTests() {
        synchronized (ENDED) {
            ENDED.clear();
        }
    }

    /**
     * Keeps what the save running on this thread just told the reader, as its last word for the
     * Downloads page. Anything said off a worker thread belongs to no save and is let go.
     */
    static void said(String message) {
        Job job = CURRENT.get();
        if (job != null && message != null) job.said = message;
    }

    /** Saves at once as a number from 1 to {@link #MAX_RUNNING_JOBS}. Anything else reads as the most. */
    static int runningLimit() {
        // The settings are read from the app's preferences, which need its context to open.
        if (Utils.getContext() == null) return MAX_RUNNING_JOBS;
        return limitFrom(Settings.DOWNLOAD_SAVES_AT_ONCE.get());
    }

    static int limitFrom(String value) {
        try {
            int wanted = Integer.parseInt(value == null ? "" : value.trim());
            return wanted >= 1 && wanted <= MAX_RUNNING_JOBS ? wanted : MAX_RUNNING_JOBS;
        } catch (NumberFormatException unreadable) {
            return MAX_RUNNING_JOBS;
        }
    }

    /**
     * Brings the pool to Saves at once as it's saved now, and answers the limit in force. Read
     * under the lock, so a save submitted on another thread can't put back an older number after
     * a newer one was applied.
     */
    static synchronized int applyLimit() {
        return applyLimit(runningLimit());
    }

    /** How many saves the pool runs at once right now: what a place in line counts against. */
    static int poolLimit() {
        return EXECUTOR.getMaximumPoolSize();
    }

    /**
     * Brings the pool to {@code limit} workers. A save already running finishes either way: a
     * lower limit starts nothing new until enough of them have ended, and a higher one starts the
     * saves waiting in line at once. The two sizes move in the order the pool allows, since the
     * core size may never pass the maximum, so raising moves the maximum first and lowering the core.
     */
    static synchronized int applyLimit(int limit) {
        int now = EXECUTOR.getMaximumPoolSize();
        if (limit > now) {
            EXECUTOR.setMaximumPoolSize(limit);
            EXECUTOR.setCorePoolSize(limit);
        } else if (limit < now) {
            EXECUTOR.setCorePoolSize(limit);
            EXECUTOR.setMaximumPoolSize(limit);
        }
        return limit;
    }

    static int queuedJobs() {
        return EXECUTOR.getQueue().size();
    }

    static int runningJobs() {
        return EXECUTOR.getActiveCount();
    }

    /**
     * Every accepted job is over, its cleanup included. The two counts above alone miss a job
     * the executor handed to a worker thread that hasn't started yet: it is in neither.
     */
    static boolean idle() {
        return ADMITTED.get() == 0 && EXECUTOR.getActiveCount() == 0 && EXECUTOR.getQueue().isEmpty();
    }

}
