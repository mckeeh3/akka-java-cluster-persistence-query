package cluster.persistence.query;

import akka.actor.AbstractLoggingActor;
import akka.actor.Props;
import akka.persistence.cassandra.query.javadsl.CassandraReadJournal;
import akka.persistence.query.EventEnvelope;
import akka.persistence.query.Offset;
import akka.persistence.query.PersistenceQuery;
import akka.stream.ActorMaterializer;
import akka.stream.alpakka.cassandra.javadsl.CassandraSource;
import akka.stream.javadsl.Sink;
import com.datastax.driver.core.*;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public class ReadSideProcessorEventTagActor extends AbstractLoggingActor {
    private final ReadSideProcessorActor.Tag tag;
    private final Session session;
    private final ActorMaterializer actorMaterializer;

    public ReadSideProcessorEventTagActor(ReadSideProcessorActor.Tag tag) {
        this.tag = tag;

        session = session();
        actorMaterializer = ActorMaterializer.create(context().system());
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .build();
    }

    private void createOffsetTable() {
        final Statement statement = new SimpleStatement(
                "CREATE TABLE IF NOT EXISTS tag_read_progress ("
                        + "tag text PRIMARY KEY,"
                        + "offset timeuuid"
                        + ");");

        CompletionStage<List<Row>> completionStage = CassandraSource.create(statement, session).runWith(Sink.seq(), actorMaterializer);
        completionStage.whenComplete((r, t) -> {
            if (t == null) {
                readTagOffset();
            } else {
                throw new RuntimeException("Create table tag_read_progress failed.", t);
            }
        });
    }

    private void readTagOffset() {
        final Statement statement = new SimpleStatement(String.format("SELECT offset FROM tag_read_progress WHERE tag = '%s'", tag.value));
        CassandraSource.create(statement, session).runWith(Sink.seq(), actorMaterializer)
                .whenComplete((r, t) -> {
                    if (t == null) {
                        readEventByTag(r);
                    } else {
                        throw new RuntimeException(String.format("Query offset of %s failed!", tag), t);
                    }
                });
    }

    private void readEventByTag(List<Row> rows) {
        if (rows.size() > 0) {
            readEventsByTag(rows.get(0).getUUID("offset"));
        } else {
            readEventsByTag();
        }
    }

    private void readEventsByTag() {
        readEventsByTag(Offset.noOffset());
    }

    private void readEventsByTag(UUID uuid) {
        readEventsByTag(Offset.timeBasedUUID(uuid));
    }

    private void readEventsByTag(Offset offset) {
        CassandraReadJournal cassandraReadJournal =
                PersistenceQuery.get(context().system()).getReadJournalFor(CassandraReadJournal.class, CassandraReadJournal.Identifier());

        cassandraReadJournal.eventsByTag(tag.value, offset).runForeach(this::handleEvent, actorMaterializer);
    }

    private void handleEvent(EventEnvelope eventEnvelope) {
        log().info("{}", eventEnvelope);
    }

    @Override
    public void preStart() {
        log().info("Start");
        createOffsetTable();
    }

    @Override
    public void postStop() {
        log().info("Stop");
    }

    static Props props(ReadSideProcessorActor.Tag tag) {
        return Props.create(ReadSideProcessorEventTagActor.class, tag);
    }

    private static Session session() {
        Cluster.Builder builder = Cluster.builder();
        for (String contactPoint : Config.contactPoints()) {
            builder.addContactPoint(contactPoint);
        }
        builder.withPort(Config.port());
        return builder.build().connect(Config.keyspace());
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
}
