package cluster.persistence;

import akka.actor.AbstractLoggingActor;
import akka.actor.Props;
import akka.persistence.cassandra.query.javadsl.CassandraReadJournal;
import akka.persistence.query.EventEnvelope;
import akka.persistence.query.Offset;
import akka.persistence.query.PersistenceQuery;
import akka.persistence.query.TimeBasedUUID;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.alpakka.cassandra.javadsl.CassandraSource;
import akka.stream.javadsl.Sink;
import com.datastax.driver.core.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

public class ReadSideProcessorEventTagActor extends AbstractLoggingActor {
    private final ReadSideProcessorActor.Tag tag;
    private final Session session;
    private final ActorMaterializer actorMaterializer;
    private static final String keyspaceName = "akka";
    private PreparedStatement preparedUpdateStatement;

    public ReadSideProcessorEventTagActor(ReadSideProcessorActor.Tag tag) {
        this.tag = tag;

        session = Cassandra.session();
        actorMaterializer = ActorMaterializer.create(context().system());

        //createOffsetTable();
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ReadSideProcessorActor.Tag.class, this::heartbeat)
                .build();
    }

    private void heartbeat(ReadSideProcessorActor.Tag tag) {
        log().info("Heartbeat {}", tag);
    }

    @Override
    public void preStart() {
        log().info("Start");

        try {
            createKeyspace(session, actorMaterializer)
                    .thenCompose(r -> createOffsetTable(session, actorMaterializer))
                    .thenCompose(r -> readTagOffset(actorMaterializer))
                    .thenAccept(this::readEventByTag)
                    .toCompletableFuture()
                    .get();

            preparedUpdateStatement = session.prepare(String.format("update %s.tag_read_progress set offset = ? where tag = ?", keyspaceName));
        } catch (InterruptedException | ExecutionException e) {
            log().error(e, "Read by tags failed.");
        }
    }

    private static CompletionStage<List<Row>> createKeyspace(Session session, Materializer materializer) {
        final String properties = "{ 'class' : 'SimpleStrategy', 'replication_factor' : 1 }";
        final String createKeyspace = String.format("CREATE KEYSPACE IF NOT EXISTS %s WITH REPLICATION = %s", keyspaceName, properties);
        final Statement statement = new SimpleStatement(createKeyspace);

        return CassandraSource.create(statement, session).runWith(Sink.seq(), materializer);
    }

    private CompletionStage<List<Row>> createOffsetTable(Session session, Materializer materializer) {
        final Statement statement = new SimpleStatement(
                String.format("CREATE TABLE IF NOT EXISTS %s.tag_read_progress (", keyspaceName)
                        + "tag text PRIMARY KEY,"
                        + "offset timeuuid"
                        + ");");

        return CassandraSource.create(statement, session).runWith(Sink.seq(), materializer);
    }

    private CompletionStage<List<Row>> readTagOffset(Materializer materializer) {
        PreparedStatement preparedStatement = session.prepare(String.format("SELECT offset FROM %s.tag_read_progress WHERE tag = ?", keyspaceName));
        return CassandraSource.create(preparedStatement.bind(tag.value), session).runWith(Sink.seq(), materializer);
    }

    private void readEventByTag(List<Row> rows) {
        if (rows.size() > 0) {
            readEventsByTag(rows.get(0).getUUID("offset"));
        } else {
            readEventsByTag(Offset.noOffset());
        }
    }

    private void readEventsByTag(UUID uuid) {
        readEventsByTag(Offset.timeBasedUUID(uuid));
    }

    private void readEventsByTag(Offset offset) {
        log().info("Read {} from offset {}", tag, offset);
        CassandraReadJournal cassandraReadJournal =
                PersistenceQuery.get(context().system()).getReadJournalFor(CassandraReadJournal.class, CassandraReadJournal.Identifier());

        cassandraReadJournal.eventsByTag(tag.value, offset).runForeach(this::handleEvent, actorMaterializer);
    }

    private void handleEvent(EventEnvelope eventEnvelope) {
        log().info("Read-side {}", eventEnvelope);
        // todo add something to do updates every Nth event
        updateTagOffset(eventEnvelope.offset());
    }

    private void updateTagOffset(Offset offset) {
        CassandraSource.create(preparedUpdateStatement.bind(((TimeBasedUUID) offset).value(), tag.value), session).runWith(Sink.seq(), actorMaterializer)
                .exceptionally(t -> {
                    throw new RuntimeException(String.format("Update tag_read_progress, %s failed!", tag), t);
                });
    }

    @Override
    public void postStop() {
        log().info("Stop");
        if (session != null) {
            session.close();
        }
    }

    static Props props(ReadSideProcessorActor.Tag tag) {
        return Props.create(ReadSideProcessorEventTagActor.class, tag);
    }
}
