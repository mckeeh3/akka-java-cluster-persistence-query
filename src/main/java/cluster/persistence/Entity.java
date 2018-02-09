package cluster.persistence;

import java.io.Serializable;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Objects;

class Entity implements Serializable {
    static final long serialVersionUID = 42L;
    final Id id;
    Balance balance;
    private static final DecimalFormat df = new DecimalFormat(",##0.00");

    private Entity(Id id, Balance balance) {
        this.id = id;
        this.balance = balance;
    }

    private Entity(String id, BigDecimal amount) {
        this(new Id(id), new Balance(amount));
    }

    static Entity deposit(String id, BigDecimal amount) {
        return new Entity(id, amount);
    }

    static Entity withdrawal(String id, BigDecimal amount) {
        return new Entity(id, BigDecimal.ZERO.subtract(amount));
    }

    static Entity deposit(Entity entity, BigDecimal amount) {
        entity.balance = new Balance(entity.balance.amount.add(amount));
        return entity;
    }

    static Entity withdrawal(Entity entity, BigDecimal amount) {
        entity.balance = new Balance(entity.balance.amount.subtract(amount));
        return entity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Entity entity = (Entity) o;
        return Objects.equals(id, entity.id) &&
                Objects.equals(balance, entity.balance);
    }

    @Override
    public int hashCode() {

        return Objects.hash(id, balance);
    }

    @Override
    public String toString() {
        return String.format("%s[%s -> %s]", getClass().getSimpleName(), id, balance);
    }

    static class Id implements Serializable {
        static final long serialVersionUID = 42L;
        final String id;

        Id(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", getClass().getSimpleName(), id);
        }
    }

    static class Balance implements Serializable {
        static final long serialVersionUID = 42L;
        final BigDecimal amount;

        Balance(BigDecimal amount) {
            this.amount = amount;
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", getClass().getSimpleName(), df.format(amount));
        }
    }
}
