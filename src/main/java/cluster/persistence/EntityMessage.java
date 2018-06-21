package cluster.persistence;

import java.io.Serializable;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;

public class EntityMessage {

    public static int numberOfEventTags = 20;

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

        @Override
        public String toString() {
            return String.format("%s[%s, %s, %s]", getClass().getSimpleName(), id, amount, time);
        }
    }
}
