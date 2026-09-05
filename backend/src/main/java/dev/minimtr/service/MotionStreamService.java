package dev.minimtr.service;

import dev.minimtr.model.dto.TransitChanged;
import dev.minimtr.model.vo.MotionFrame;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class MotionStreamService {
    private static final Logger log=LoggerFactory.getLogger(MotionStreamService.class);
    private record Key(String operator,String mode) {}
    private record Message(String event,Object data) {}
    private final Set<Subscription> subscriptions=ConcurrentHashMap.newKeySet();
    private final TransitFrames frames;
    public MotionStreamService(TransitFrames frames) { this.frames=frames; }
    public SseEmitter subscribe(String operator,String mode) {
        var initial=frames.frame(operator,mode,null);
        var sub=new Subscription(new Key(operator,initial.mode()));
        subscriptions.add(sub);
        sub.emitter.onCompletion(sub::close);
        sub.emitter.onTimeout(sub::close);
        sub.emitter.onError(e -> sub.close());
        sub.publish(new Message(null,initial));
        sub.sender=Thread.ofVirtual().name("motion-sse").start(sub::send);
        return sub.emitter;
    }
    public SseEmitter changes(String operator) {
        frames.network(operator);
        var sub=new Subscription(new Key(operator,"events"));
        subscriptions.add(sub);
        sub.emitter.onCompletion(sub::close);
        sub.emitter.onTimeout(sub::close);
        sub.emitter.onError(error -> sub.close());
        sub.publish(new Message("data-changed",operator));
        sub.sender=Thread.ofVirtual().name("transit-events").start(sub::send);
        return sub.emitter;
    }
    @Scheduled(fixedDelay=1000)
    public void tick() {
        var keys=new HashSet<Key>();
        subscriptions.forEach(s -> keys.add(s.key));
        for(var key:keys) {
            if(key.mode().equals("events")) continue;
            Message message;
            try { message=new Message(null,frames.frame(key.operator(),key.mode(),null)); }
            catch(RuntimeException error) {
                log.warn("Motion frame failed for {}",key,error);
                message=new Message("source-error",Map.of("error",Objects.requireNonNullElse(error.getMessage(),"Frame failed")));
            }
            var value=message;
            subscriptions.stream().filter(s -> s.key.equals(key)).forEach(s -> s.publish(value));
        }
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void changed(TransitChanged change) {
        subscriptions.stream().filter(s -> s.key.operator().equals(change.operator())).forEach(s -> {
            if(s.key.mode().equals("events")) s.publish(new Message("data-changed",change.operator()));
            else s.changed=true;
        });
    }
    private final class Subscription {
        final Key key;
        final SseEmitter emitter=new SseEmitter(60_000L);
        Message pending;
        volatile boolean changed;
        boolean closed;
        volatile Thread sender;
        Subscription(Key key) { this.key=key; }
        synchronized void publish(Message value) { if(!closed) { pending=value; notifyAll(); } }
        synchronized Message next() throws InterruptedException {
            while(!closed && pending==null) wait();
            if(closed) return null;
            var value=pending; pending=null; return value;
        }
        void send() {
            try {
                emitter.send(SseEmitter.event().reconnectTime(3000));
                Message value;
                while((value=next())!=null) {
                    if(changed) { changed=false; emitter.send(SseEmitter.event().name("data-changed").data(key.operator())); }
                    var event=SseEmitter.event().data(value.data());
                    if(value.event()!=null) event.name(value.event());
                    emitter.send(event);
                }
            } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            catch(IOException | IllegalStateException error) { log.debug("Motion stream disconnected",error); }
            catch(RuntimeException error) { log.error("Motion stream failed",error); }
            finally { close(); emitter.complete(); }
        }
        synchronized void close() {
            if(closed) return;
            closed=true; pending=null; notifyAll(); subscriptions.remove(this);
            if(sender!=null && sender!=Thread.currentThread()) sender.interrupt();
        }
    }
    @PreDestroy public void close() { subscriptions.forEach(Subscription::close); }
}
