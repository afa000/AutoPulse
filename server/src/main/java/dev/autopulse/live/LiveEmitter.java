package dev.autopulse.live;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Distinguish container-owned I/O failure from intentional application retirement. */
final class LiveEmitter extends SseEmitter {
    private final CountDownLatch responseReady=new CountDownLatch(1);
    private final AtomicBoolean containerClosing=new AtomicBoolean();
    LiveEmitter() { super(120_000L); }
    @Override protected void extendResponse(ServerHttpResponse response) {
        super.extendResponse(response);responseReady.countDown();
    }
    boolean awaitResponse() throws InterruptedException { return responseReady.await(5,TimeUnit.SECONDS); }
    boolean awaitingContainerCompletion() { return containerClosing.get(); }
    void containerClosing() { containerClosing.set(true); }

    @Override public void send(SseEventBuilder event) throws IOException {
        // Mark ownership before releasing the emitter's write lock: retirement may already be queued.
        writeLock.lock();
        try { super.send(event); }
        catch(IOException failure) { containerClosing.set(true);throw failure; }
        finally { writeLock.unlock(); }
    }
    void retire() {
        writeLock.lock();
        try {
            // Normal complete flushes under Spring's async lock, which can invert
            // Tomcat's error-listener/socket lock order. Dispatch retirement without flushing.
            if(!containerClosing.get()) super.completeWithError(
                    new org.springframework.web.context.request.async.AsyncRequestNotUsableException("Live stream retired; reconnect"));
        }
        finally { writeLock.unlock(); }
    }
}
