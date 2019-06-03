package cluster.persistence;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

class EntityQueryActor extends AbstractLoggingActor {
    private final ActorRef shardRegion;
    private Cancellable ticker;
    private FiniteDuration tickInterval = Duration.create(2, TimeUnit.SECONDS);
    private EntityMessage.Query lastQuery;
    private final Receive sending;
    private final Receive receiving;

    {
        sending = receiveBuilder()
                .matchEquals("tick", t -> tickSending())
                .match(EntityMessage.QueryAck.class, this::queryAckSending)
                .match(EntityMessage.QueryAckNotFound.class, this::queryAckNotFoundSending)
                .build();

        receiving = receiveBuilder()
                .matchEquals("tick", t -> tickReceiving())
                .match(EntityMessage.QueryAck.class, this::queryAckReceiving)
                .match(EntityMessage.QueryAckNotFound.class, this::queryAckNotFoundReceiving)
                .build();
    }

    EntityQueryActor(ActorRef shardRegion) {
        this.shardRegion = shardRegion;
    }

    @Override
    public Receive createReceive() {
        return sending;
    }

    private void tickSending() {
        lastQuery = query();
        log().info("{} -> {}", lastQuery, shardRegion);
        shardRegion.tell(lastQuery, self());
        getContext().become(receiving);
    }

    private void queryAckSending(EntityMessage.QueryAck queryAck) {
        log().info("(late) {} <- {}", queryAck, sender());
    }

    private void queryAckNotFoundSending(EntityMessage.QueryAckNotFound queryAckNotFound) {
        log().info("(late) {} <- {}", queryAckNotFound, sender());
    }

    private void tickReceiving() {
        log().warning("No query response to {}", lastQuery);
        getContext().become(sending);
    }

    private void queryAckReceiving(EntityMessage.QueryAck queryAck) {
        log().info("{} <- {}", queryAck, sender());
        getContext().become(sending);
    }

    private void queryAckNotFoundReceiving(EntityMessage.QueryAckNotFound queryAckNotFound) {
        log().info("{} <- {}", queryAckNotFound, sender());
        getContext().become(sending);
    }

    private EntityMessage.Query query() {
        return new EntityMessage.Query(Random.entityId(1, 100));
    }

    @Override
    public void preStart() {
        log().info("Start");
        ticker = context().system().scheduler().schedule(
                tickInterval,
                tickInterval,
                self(),
                "tick",
                context().system().dispatcher(),
                null
        );
    }

    @Override
    public void postStop() {
        log().info("Stop");
        ticker.cancel();
    }

    static Props props(ActorRef shardRegion) {
        return Props.create(EntityQueryActor.class, shardRegion);
    }
}
