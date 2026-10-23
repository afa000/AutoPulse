package dev.autopulse.live;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;

class LiveEmitterTests {
    /** Real Spring emitter/lock with a deterministic framework-handler boundary, no servlet sockets. */
    static final class Handler implements InvocationHandler {
        final AtomicInteger normalCompletions=new AtomicInteger(),errorCompletions=new AtomicInteger();
        final AtomicReference<Throwable> completionFailure=new AtomicReference<>();
        volatile boolean failSend;
        CountDownLatch sending,releaseSend;
        void attach(LiveEmitter emitter) throws Exception {
            Class<?> type=Class.forName(ResponseBodyEmitter.class.getName()+"$Handler");
            var initialize=ResponseBodyEmitter.class.getDeclaredMethod("initialize",type);initialize.setAccessible(true);
            initialize.invoke(emitter,Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},this));
        }
        @Override public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
            switch(method.getName()) {
                case "send" -> { if(sending!=null){sending.countDown();if(!releaseSend.await(2,TimeUnit.SECONDS))throw new AssertionError("test send not released");}if(failSend)throw new IOException("controlled broken connection"); }
                case "complete" -> normalCompletions.incrementAndGet();
                case "completeWithError" -> {errorCompletions.incrementAndGet();completionFailure.set((Throwable)args[0]);}
                default -> { }
            }
            return null;
        }
    }
    @Test void applicationRetirementDoesNotPerformTheNormalCompletionFlush() throws Exception {
        var emitter=new LiveEmitter();var handler=new Handler();handler.attach(emitter);
        emitter.retire();
        assertThat(handler.normalCompletions).hasValue(0);
        assertThat(handler.errorCompletions).hasValue(1);
        assertThat(handler.completionFailure.get()).isInstanceOf(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class);
    }
    @Test void sendIoFailureBelongsToContainerAndMustNotBeCompletedAgain() throws Exception {
        var emitter=new LiveEmitter();var handler=new Handler();handler.failSend=true;handler.attach(emitter);
        assertThatThrownBy(()->emitter.send(SseEmitter.event().data("fixture"))).isInstanceOf(IOException.class);
        assertThat(emitter.awaitingContainerCompletion()).isTrue();emitter.retire();
        assertThat(handler.normalCompletions).hasValue(0);assertThat(handler.errorCompletions).hasValue(0);
    }
    @Test void queuedRetirementCannotWinTheOwnershipRaceWithAnInFlightFailedSend() throws Exception {
        var emitter=new LiveEmitter();var handler=new Handler();handler.failSend=true;
        handler.sending=new CountDownLatch(1);handler.releaseSend=new CountDownLatch(1);handler.attach(emitter);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var send=workers.submit(()->{assertThatThrownBy(()->emitter.send(SseEmitter.event().data("fixture"))).isInstanceOf(IOException.class);});
            assertThat(handler.sending.await(2,TimeUnit.SECONDS)).isTrue();var retire=workers.submit(emitter::retire);
            handler.releaseSend.countDown();send.get(2,TimeUnit.SECONDS);retire.get(2,TimeUnit.SECONDS);
        } finally {handler.releaseSend.countDown();}
        assertThat(handler.normalCompletions).hasValue(0);assertThat(handler.errorCompletions).hasValue(0);
    }
}
