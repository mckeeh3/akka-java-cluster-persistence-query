package cluster.persistence;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import scala.concurrent.duration.FiniteDuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

class ReadSideProcessorHeartbeatSingletonActor extends AbstractLoggingActor {
    private final ActorRef shardRegion;
    private Cancellable heartbeat;

    public ReadSideProcessorHeartbeatSingletonActor(ActorRef shardRegion) {
        this.shardRegion = shardRegion;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Heartbeat.class, h -> heartbeat())
                .build();
    }

    private void heartbeat() {
        log().info("Heartbeat {}", ReadSideProcessorActor.Tag.tags());
        ReadSideProcessorActor.Tag.tags().forEach(tag -> shardRegion.tell(tag, self()));
    }

    private void scheduleHeartbeat() {
        FiniteDuration tickInterval = heartbeatInterval();

        heartbeat = context().system().scheduler().schedule(
                tickInterval,
                tickInterval,
                self(),
                new Heartbeat(),
                context().dispatcher(),
                ActorRef.noSender()
        );
    }

    private FiniteDuration heartbeatInterval() {
        Duration tickInterval = context().system().settings().config().getDuration("read-side-processor.heartbeat-interval");
        return FiniteDuration.create(tickInterval.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void preStart() {
        log().info("Start");
        scheduleHeartbeat();
    }

    @Override
    public void postStop() {
        log().info("Stop");
        heartbeat.cancel();
    }

    static Props props(ActorRef shardRegion) {
        return Props.create(ReadSideProcessorHeartbeatSingletonActor.class, shardRegion);
    }

    private static class Heartbeat {
    }
}
