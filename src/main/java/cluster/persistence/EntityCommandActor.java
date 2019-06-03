package cluster.persistence;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

class EntityCommandActor extends AbstractLoggingActor {
    private final ActorRef shardRegion;
    private Cancellable ticker;
    private FiniteDuration tickInterval = Duration.create(2, TimeUnit.SECONDS);
    private EntityMessage.EntityCommand lastCommand;
    private final Receive sending;
    private final Receive receiving;

    {
        sending = receiveBuilder()
                .matchEquals("tick", t -> tickSending())
                .match(EntityMessage.CommandAck.class, this::commandAckSending)
                .build();

        receiving = receiveBuilder()
                .matchEquals("tick", t -> tickReceiving())
                .match(EntityMessage.CommandAck.class, this::commandAckReceiving)
                .build();
    }

    EntityCommandActor(ActorRef shardRegion) {
        this.shardRegion = shardRegion;
    }

    @Override
    public Receive createReceive() {
        return sending;
    }

    private void commandAckSending(EntityMessage.CommandAck commandAck) {
        log().info("Received (late) {} {}", commandAck, sender());
    }

    private void tickSending() {
        lastCommand = command();
        log().info("{} -> {}", lastCommand, shardRegion);
        shardRegion.tell(lastCommand, self());
        getContext().become(receiving);
    }

    private void commandAckReceiving(EntityMessage.CommandAck commandAck) {
        java.time.Duration duration = java.time.Duration.between(commandAck.entityEvent.time, Instant.now());
        String durationSeconds = String.format("%.3fs", duration.toMillis() / 1000.0);
        log().info("Received ({}) {} {}", durationSeconds, commandAck, sender());
        getContext().become(sending);
    }

    private void tickReceiving() {
        log().warning("No response to last command {}", lastCommand);
        getContext().become(sending);
    }

    private EntityMessage.EntityCommand command() {
        Entity.Id id = Random.entityId(1, 100);
        BigDecimal amount = Random.amount(-10000, 10000);

        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            return new EntityMessage.DepositCommand(id, new EntityMessage.Amount(amount));
        } else {
            return new EntityMessage.WithdrawalCommand(id, new EntityMessage.Amount(BigDecimal.valueOf(-1).multiply(amount)));
        }
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
        return Props.create(EntityCommandActor.class, shardRegion);
    }
}
