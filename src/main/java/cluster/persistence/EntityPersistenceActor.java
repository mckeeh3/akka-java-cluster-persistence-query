package cluster.persistence;

import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.ReceiveTimeout;
import akka.cluster.sharding.ShardRegion;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.persistence.AbstractPersistentActor;
import akka.persistence.RecoveryCompleted;
import akka.persistence.journal.Tagged;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

class EntityPersistenceActor extends AbstractPersistentActor {
    private final LoggingAdapter log = Logging.getLogger(context().system(), this);
    private Entity entity;
    private final FiniteDuration receiveTimeout = Duration.create(60, TimeUnit.SECONDS);

    @Override
    public Receive createReceiveRecover() {
        return receiveBuilder()
                .match(EntityMessage.DepositEvent.class, this::depositRecover)
                .match(EntityMessage.WithdrawalEvent.class, this::withdrawalRecover)
                .match(RecoveryCompleted.class, c -> recoveryCompleted())
                .match(EntityMessage.Query.class, this::query)
                .build();
    }

    private void depositRecover(EntityMessage.DepositEvent depositEvent) {
        update(depositEvent);
        log.info("Recover {} {}", entity, depositEvent);
    }

    private void withdrawalRecover(EntityMessage.WithdrawalEvent withdrawalEvent) {
        update(withdrawalEvent);
        log.info("Recover {} {}", entity, withdrawalEvent);
    }

    private void recoveryCompleted() {
        log.debug("Recovery completed {}", entity);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(EntityMessage.DepositCommand.class, this::deposit)
                .match(EntityMessage.WithdrawalCommand.class, this::withdrawal)
                .match(EntityMessage.Query.class, this::query)
                .matchEquals(ReceiveTimeout.getInstance(), t -> passivate())
                .build();
    }

    private void deposit(EntityMessage.DepositCommand depositCommand) {
        log.info("{} <- {}", depositCommand, sender());
        persist(tagCommand(depositCommand), taggedEvent -> handleDeposit(depositCommand, taggedEvent));
    }

    private void handleDeposit(EntityMessage.DepositCommand depositCommand, Tagged taggedEvent) {
        if (taggedEvent.payload() instanceof EntityMessage.DepositEvent) {
            EntityMessage.DepositEvent depositEvent = (EntityMessage.DepositEvent) taggedEvent.payload();
            update(depositEvent);
            log.info("{} {} {} -> {}", depositCommand, depositEvent, entity, sender());
            sender().tell(EntityMessage.CommandAck.from(depositCommand, depositEvent), self());
        }
    }

    private void withdrawal(EntityMessage.WithdrawalCommand withdrawalCommand) {
        log.info("{} <- {}", withdrawalCommand, sender());
        persist(tagCommand(withdrawalCommand), taggedEvent -> handleWithdrawal(withdrawalCommand, taggedEvent));
    }

    private void handleWithdrawal(EntityMessage.WithdrawalCommand withdrawalCommand, Tagged taggedEvent) {
        if (taggedEvent.payload() instanceof EntityMessage.WithdrawalEvent) {
            EntityMessage.WithdrawalEvent withdrawalEvent = (EntityMessage.WithdrawalEvent) taggedEvent.payload();
            update(withdrawalEvent);
            log.info("{} {} {} -> {}", withdrawalCommand, withdrawalEvent, entity, sender());
            sender().tell(EntityMessage.CommandAck.from(withdrawalCommand, withdrawalEvent), self());
        }
    }

    private static Tagged tagCommand(EntityMessage.DepositCommand depositCommand) {
        return new Tagged(new EntityMessage.DepositEvent(depositCommand), EntityMessage.eventTag(depositCommand));
    }

    private static Tagged tagCommand(EntityMessage.WithdrawalCommand withdrawalCommand) {
        return new Tagged(new EntityMessage.WithdrawalEvent(withdrawalCommand), EntityMessage.eventTag(withdrawalCommand));
    }

    private void update(EntityMessage.DepositEvent depositEvent) {
        entity = entity == null
                ? Entity.deposit(depositEvent.id.id, depositEvent.amount.amount)
                : Entity.deposit(entity, depositEvent.amount.amount);
    }

    private void update(EntityMessage.WithdrawalEvent withdrawalEvent) {
        entity = entity == null
                ? Entity.withdrawal(withdrawalEvent.id.id, withdrawalEvent.amount.amount)
                : Entity.withdrawal(entity, withdrawalEvent.amount.amount);
    }

    private void query(EntityMessage.Query query) {
        if (entity == null) {
            sender().tell(EntityMessage.QueryAckNotFound.from(query), self());
        } else {
            sender().tell(EntityMessage.QueryAck.from(query, entity), self());
        }
    }

    private void passivate() {
        context().parent().tell(new ShardRegion.Passivate(PoisonPill.getInstance()), self());
    }

    @Override
    public String persistenceId() {
        return entity == null ? self().path().name() : entity.id.id;
    }

    @Override
    public void preStart() {
        log.info("Start");
        context().setReceiveTimeout(receiveTimeout);
    }

    @Override
    public void postStop() {
        log.info("Stop passivate {}", entity == null
                ? String.format("(entity %s not initialized)", self().path().name())
                : entity.id);
    }

    static Props props() {
        return Props.create(EntityPersistenceActor.class);
    }
}
