package io.github.jbellis.brokk.gui.mop.webview;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MOPBridge {
    private final WebEngine engine;
    private final ScheduledExecutorService xmit = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "MOPBridge");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean pending = new AtomicBoolean();
    private final StringBuilder buf = new StringBuilder();
    private final AtomicInteger epoch = new AtomicInteger();
    private final Map<Integer, CompletableFuture<Void>> awaiting = new ConcurrentHashMap<>();
    private volatile boolean isNewMessageHolder = false;

    public MOPBridge(WebEngine engine) {
        this.engine = engine;
    }

    public void append(String txt, boolean newMsg) {
        if (txt.isEmpty()) return;
        synchronized (buf) {
            buf.append(txt);
            if (newMsg) {
                isNewMessageHolder = true;
            }
        }
        scheduleSend();
    }

    private void scheduleSend() {
        if (pending.compareAndSet(false, true)) {
            xmit.schedule(() -> {
                String chunk;
                boolean newMsgForChunk;
                synchronized (buf) {
                    chunk = buf.toString();
                    buf.setLength(0);
                    newMsgForChunk = isNewMessageHolder;
                    isNewMessageHolder = false;
                }

                try {
                    if (!chunk.isEmpty()) {
                        int e = epoch.incrementAndGet();
                        String json = toJson(chunk, newMsgForChunk, e);
                        var promise = new CompletableFuture<Void>();
                        awaiting.put(e, promise);
                        Platform.runLater(() -> engine.executeScript("window.brokk.onEvent(" + json + ")"));
                    }
                } finally {
                    pending.set(false);
                    if (!buf.isEmpty()) {
                        scheduleSend();
                    }
                }
            }, 20, TimeUnit.MILLISECONDS);
        }
    }

    public void onAck(int e) {
        var p = awaiting.remove(e);
        if (p != null) {
            p.complete(null);
        }
    }

    public CompletableFuture<Void> flushAsync() {
        return awaiting.getOrDefault(epoch.get(), CompletableFuture.completedFuture(null));
    }

    private static String toJson(String chunk, boolean newMsg, int epoch) {
        return """
               {"type":"chunk","text":%s,"isNew":%s,"epoch":%d}
               """.formatted(escape(chunk), newMsg, epoch);
    }

    private static String escape(String text) {
        var escaped = text.replace("\\", "\\\\")
                          .replace("\"", "\\\"")
                          .replace("\b", "\\b")
                          .replace("\f", "\\f")
                          .replace("\n", "\\n")
                          .replace("\r", "\\r")
                          .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    public void shutdown() {
        xmit.shutdownNow();
    }
}
