package cluster.persistence;

import akka.cluster.sharding.ShardRegion;

import java.io.Serializable;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class EntityMessage {
    static class Amount implements Serializable {
        static final long serialVersionUID = 42L;
        final BigDecimal amount;
        private static final DecimalFormat df = new DecimalFormat(",##0.00");

        Amount(BigDecimal amount) {
            this.amount = amount;
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", getClass().getSimpleName(), df.format(amount));
        }
    }

    static class EntityCommand implements Serializable {
        static final long serialVersionUID = 42L;
        final long messageNanoTime;
        final Entity.Id id;
        final Amount amount;

        private EntityCommand(Entity.Id id, Amount amount) {
            messageNanoTime = System.nanoTime();
            this.id = id;
            this.amount = amount;
        }
    }

    static class DepositCommand extends EntityCommand {
        static final long serialVersionUID = 42L;

        DepositCommand(Entity.Id id, Amount amount) {
            super(id, amount);
        }

        @Override
        public String toString() {
            return String.format("%s[%s, %s, %dus]", getClass().getSimpleName(), id, amount, messageNanoTime);
        }
    }

    static class WithdrawalCommand extends EntityCommand {
        static final long serialVersionUID = 42L;

        WithdrawalCommand(Entity.Id id, Amount amount) {
            super(id, amount);
        }

        @Override
        public String toString() {
            return String.format("%s[%s, %s, %dus]", getClass().getSimpleName(), id, amount, messageNanoTime);
        }
    }

    static class EntityEvent implements Serializable {
        static final long serialVersionUID = 42L;
        final Entity.Id id;
        final Amount amount;
        final Instant time = Instant.now();

        private EntityEvent(Entity.Id id, Amount amount) {
            this.id = id;
            this.amount = amount;
        }
    }

    static class DepositEvent extends EntityEvent {
        static final long serialVersionUID = 42L;

        DepositEvent(Entity.Id id, Amount amount) {
            super(id, amount);
        }

        DepositEvent(DepositCommand depositCommand) {
            this(depositCommand.id, depositCommand.amount);
        }

        @Override
        public String toString() {
            return String.format("%s[%s, %s, %s]", getClass().getSimpleName(), id, amount, time);
        }
    }

    static class WithdrawalEvent extends EntityEvent {
        static final long serialVersionUID = 42L;

        WithdrawalEvent(Entity.Id id, Amount amount) {
            super(id, amount);
        }

        WithdrawalEvent(WithdrawalCommand withdrawalCommand) {
            this(withdrawalCommand.id, withdrawalCommand.amount);
        }

        @Override
        public String toString() {
            return String.format("%s[%s, %s, %s]", getClass().getSimpleName(), id, amount, time);
        }
    }

    static class CommandAck implements Serializable {
        static final long serialVersionUID = 42L;
        final long commandTime;
        final EntityEvent entityEvent;

        private CommandAck(long commandTime, EntityEvent entityEvent) {
            this.commandTime = commandTime;
            this.entityEvent = entityEvent;
        }

        static CommandAck from(EntityCommand entityCommand, EntityEvent entityEvent) {
            return new CommandAck(entityCommand.messageNanoTime, entityEvent);
        }

        @Override
        public String toString() {
            final double elapsed = (System.nanoTime() - commandTime) / 1000000000.0;
            return String.format("%s[%s, elapsed %.9fs, %dus]", getClass().getSimpleName(), entityEvent, elapsed, commandTime);
        }
    }

    static class Query implements Serializable {
        static final long serialVersionUID = 42L;
        final long messageNanoTime;
        final Entity.Id id;

        Query(Entity.Id id) {
            messageNanoTime = System.nanoTime();
            this.id = id;
        }

        @Override
        public String toString() {
            return String.format("%s[%dus, %s]", getClass().getSimpleName(), messageNanoTime, id);
        }
    }

    static class QueryAck implements Serializable {
        static final long serialVersionUID = 42L;
        final long queryTime;
        final Entity entity;

        private QueryAck(long queryTime, Entity entity) {
            this.queryTime = queryTime;
            this.entity = entity;
        }

        static QueryAck from(Query query, Entity entity) {
            return new QueryAck(query.messageNanoTime, entity);
        }

        @Override
        public String toString() {
            final double elapsed = (System.nanoTime() - queryTime) / 1000000000.0;
            return String.format("%s[%s, elapsed %.9fs, %dus]", getClass().getSimpleName(), entity, elapsed, queryTime);
        }
    }

    static class QueryAckNotFound implements Serializable {
        static final long serialVersionUID = 42L;
        final long queryTime;
        final Entity.Id id;

        private QueryAckNotFound(long queryTime, Entity.Id id) {
            this.queryTime = queryTime;
            this.id = id;
        }

        static QueryAckNotFound from(Query query) {
            return new QueryAckNotFound(query.messageNanoTime, query.id);
        }

        @Override
        public String toString() {
            final double elapsed = (System.nanoTime() - queryTime) / 1000000000.0;
            return String.format("%s[%s, elapsed %.9fs, %ds]", getClass().getSimpleName(), id, elapsed, queryTime);
        }
    }

    static Set<String> eventTag(EntityCommand entityCommand) {
        int numberOfEventTags = 5;
        return new HashSet<>(Collections.singletonList(String.format("%d", entityCommand.id.id.hashCode() % numberOfEventTags)));
    }
    public static final int numberOfShards = 15;

    static ShardRegion.MessageExtractor messageExtractor() {

        return new ShardRegion.MessageExtractor() {
            @Override
            public String shardId(Object message) {
                return extractShardIdFromCommand(message);
            }

            @Override
            public String entityId(Object message) {
                return extractEntityIdFromCommand(message);
            }

            @Override
            public Object entityMessage(Object message) {
                return message;
            }

            private String extractShardIdFromCommand(Object message) {
                if (message instanceof DepositCommand) {
                    return ((DepositCommand) message).id.id.hashCode() % numberOfShards + "";
                } else if (message instanceof WithdrawalCommand) {
                    return ((WithdrawalCommand) message).id.id.hashCode() % numberOfShards + "";
                } else if (message instanceof Query) {
                    return ((Query) message).id.id.hashCode() % numberOfShards + "";
                } else {
                    return null;
                }
            }

            private String extractEntityIdFromCommand(Object message) {
                if (message instanceof DepositCommand) {
                    return ((DepositCommand) message).id.id;
                } else if (message instanceof WithdrawalCommand) {
                    return ((WithdrawalCommand) message).id.id;
                } else if (message instanceof Query) {
                    return ((Query) message).id.id;
                } else {
                    return null;
                }
            }
        };
    }
}
