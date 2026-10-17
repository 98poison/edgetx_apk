package com.edgetx.droidui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * An HTTP GET for the diagnostics page, off the main thread.
 *
 * <p>{@link #getAsync} hands the work to one background thread and calls back on
 * the main thread. The obvious alternative - calling {@link #get} from a timer on
 * the UI thread - is what this replaced, and it froze the page for up to 800 ms
 * every second. The timeout is reached on every refresh whenever rcbridge is not
 * running, which is an entirely ordinary state, so the diagnostics page would
 * have been permanently unresponsive exactly when it was needed.
 *
 * <p>Every failure returns null rather than throwing. The page's job in that
 * state is to say so, not to put an exception on the screen.
 */
final class BridgeHttp {

    private BridgeHttp() {}

    /**
     * One thread, not one per request.
     *
     * <p>A single-threaded executor runs the queue in order, and the caller
     * checks for staleness before enqueueing, so a refresh that takes longer than
     * the interval is simply superseded rather than piling up behind itself.
     */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            final Thread t = new Thread(r, "bridge-http");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Called on the main thread with the body, or null if it could not be fetched. */
    interface Callback {
        void onBody(String body);
    }

    static void getAsync(String url, int timeoutMs, final Callback callback) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final String body = get(url, timeoutMs);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            callback.onBody(body);
                        } catch (Throwable t) {
                            Log.w("EdgeTXUI", "bridge: the diagnostics callback threw", t);
                        }
                    }
                });
            }
        });
    }

    /**
     * Blocking, and must not run on the main thread - see {@link #getAsync}.
     *
     * @param timeoutMs how long to wait for the whole exchange. Short on purpose:
     *                  the request goes to loopback, so anything slower than this
     *                  means nothing is listening, and waiting longer only delays
     *                  saying so.
     * @return the body, or null if it could not be fetched or was not HTTP 200.
     */
    static String get(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) {
                return null;
            }
            final InputStream in = c.getInputStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                // The payload is a few kilobytes. A runaway one is a bug on the
                // other end, and reading it forever would be this app's problem.
                if (out.size() > 512 * 1024) {
                    return null;
                }
            }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}