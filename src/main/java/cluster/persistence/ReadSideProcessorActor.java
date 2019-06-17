package cluster.persistence;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.sharding.ShardRegion;
import akka.pattern.BackoffOpts;
import akka.pattern.BackoffSupervisor;
import akka.routing.MurmurHash;
import scala.concurrent.duration.FiniteDuration;

import java.io.Serializable;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

class ReadSideProcessorActor extends AbstractLoggingActor {
    private ActorRef readSideProcessorEventTag;

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Tag.class, this::heartbeat)
                .build();
    }

    private void heartbeat(Tag tag) {
        log().info("Heartbeat {}", tag);

        if (readSideProcessorEventTag == null) {
            // todo figure out which supervisor to use
            BackoffSupervisor.props(BackoffOpts.onFailure(
                    ReadSideProcessorEventTagActor.props(tag),
                    String.format("tag-%s", tag.value),
                    FiniteDuration.create(1, TimeUnit.SECONDS),
                    FiniteDuration.create(39, TimeUnit.SECONDS),
                    0.2
            ));
            Props props = BackoffSupervisor.props(
                    ReadSideProcessorEventTagActor.props(tag),
                    String.format("tag-%s", tag.value),
                    FiniteDuration.create(1, TimeUnit.SECONDS),
                    FiniteDuration.create(39, TimeUnit.SECONDS),
                    0.2
            );
            readSideProcessorEventTag = context().system().actorOf(props, String.format("supervisor-%s", tag.value));
        }
    }

    @Override
    public void preStart() {
        log().info("Start");
    }

    @Override
    public void postStop() {
        log().info("Stop");
    }

    static Props props() {
        return Props.create(ReadSideProcessorActor.class);
    }

    static class Tag implements Serializable {
        final String value;

        Tag(String value) {
            this.value = value;
        }

        static Collection<Tag> tags() {
            return IntStream.range(0, EntityMessage.numberOfEventTags)
                    .mapToObj(i -> new Tag(i + ""))
                    .collect(Collectors.toList());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Tag tag = (Tag) o;
            return Objects.equals(value, tag.value);
        }

        @Override
        public int hashCode() {
            return MurmurHash.stringHash(value);
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", getClass().getSimpleName(), value);
        }
    }

    static ShardRegion.MessageExtractor messageExtractor() {
        return new ShardRegion.MessageExtractor() {
            @Override
            public String shardId(Object message) {
                return message instanceof Tag
                        ? message.hashCode() % EntityMessage.numberOfEventTags + ""
                        : null;
            }

            @Override
            public String entityId(Object message) {
                return message instanceof Tag
                        ? ((Tag) message).value
                        : null;
            }

            @Override
            public Object entityMessage(Object message) {
                return message;
            }
        };
    }
}
