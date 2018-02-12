package cluster.persistence.query;

import akka.NotUsed;
import akka.actor.AbstractLoggingActor;
import akka.actor.Props;
import akka.persistence.cassandra.query.javadsl.CassandraReadJournal;
import akka.persistence.query.EventEnvelope;
import akka.persistence.query.Offset;
import akka.persistence.query.PersistenceQuery;
import akka.persistence.query.TimeBasedUUID;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Source;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class PullTaggedJournalEventsActor extends AbstractLoggingActor {
    private final String tag;
    private CassandraJournal cassandraJournal;
    private Optional<TagReadProgress> tagReadProgress;

    private PullTaggedJournalEventsActor(String tag) {
        this.tag = tag;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .build();
    }

    @Override
    public void preStart() {
        log().info("Start for tag {}", tag);

        cassandraJournal = new CassandraJournal();
        tagReadProgress = cassandraJournal.getTagReadProgress(tag);

        runPullJournalStream();
    }

    private void runPullJournalStream() {
        ActorMaterializer materializer = ActorMaterializer.create(getContext().getSystem());

        CassandraReadJournal cassandraReadJournal =
                PersistenceQuery.get(getContext().getSystem()).getReadJournalFor(CassandraReadJournal.class, CassandraReadJournal.Identifier());

        Source<EventEnvelope, NotUsed> source = cassandraReadJournal.eventsByTag(tag, offset());
        source.runForeach(this::handleEvent, materializer);
    }

    private Offset offset() {
        return tagReadProgress.isPresent()
                ? new TimeBasedUUID(tagReadProgress.get().offset)
                : Offset.noOffset();
    }

    private void handleEvent(EventEnvelope eventEnvelope) {
        getContext().getParent().tell(eventEnvelope.event(), getSelf());
        cassandraJournal.setTagReadProgress(new TagReadProgress(tag, eventEnvelope.offset()));
    }

    @Override
    public void postStop() {
        log().info("Stop for tag {}", tag);
        cassandraJournal.close();
    }

    static Props props(String tag) {
        return Props.create(PullTaggedJournalEventsActor.class, tag);
    }

    private static class Config {
        static List<String> contactPoints() {
            List<String> contactPoints = new ArrayList<>();

            for (ConfigValue value : ConfigFactory.load().getList("cassandra-journal.contact-points")) {
                if (value.valueType().equals(ConfigValueType.STRING)) {
                    contactPoints.add((String) value.unwrapped());
                }
            }
            return contactPoints;
        }

        static int port() {
            return ConfigFactory.load().getInt("cassandra-journal.port");
        }

        static String keyspace() {
            return ConfigFactory.load().getString("cassandra-journal.keyspace");
        }
    }

    private static class CassandraJournal {
        private final Cluster cluster;
        private final Session session;
        private final PreparedStatement select;
        private final PreparedStatement insert;

        CassandraJournal() {
            cluster = cluster();
            session = session();

            createOffsetTable();
            select = session.prepare("SELECT offset FROM tag_read_progress WHERE tag = ?");
            insert = session.prepare("INSERT INTO tag_read_progress (tag, offset) values (?, ?)");
        }

        void close() {
            session.close();
            cluster.close();
        }

        private Cluster cluster() {
            Cluster.Builder builder = Cluster.builder();
            for (String contactPoint : Config.contactPoints()) {
                builder.addContactPoint(contactPoint);
            }
            builder.withPort(Config.port());
            return builder.build();
        }

        private Session session() {
            return cluster.connect(Config.keyspace());
        }

        private void createOffsetTable() {
            session.execute("CREATE TABLE IF NOT EXISTS tag_read_progress ("
                    + "tag text PRIMARY KEY,"
                    + "offset timeuuid"
                    + ");"
            );
        }

        Optional<TagReadProgress> getTagReadProgress(String tag) {
            ResultSet resultSet = session.execute(select.bind(tag));
            return resultSet.isExhausted()
                    ? Optional.empty()
                    : Optional.of(new TagReadProgress(tag, resultSet.one().getUUID("offset")));
        }

        void setTagReadProgress(TagReadProgress tagReadProgress) {
            session.execute(insert.bind(tagReadProgress.tag, tagReadProgress.offset));
        }
    }

    private static class TagReadProgress {
        final String tag;
        final UUID offset;

        TagReadProgress(String tag, UUID offset) {
            this.tag = tag;
            this.offset = offset;
        }

        TagReadProgress(String tag, Offset offset) {
            this(tag, ((TimeBasedUUID) offset).value());
        }

        @Override
        public String toString() {
            return String.format("%s[%s, %s]", getClass().getSimpleName(), tag, offset);
        }
    }
}
