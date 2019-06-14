package cluster.persistence;

import akka.NotUsed;
import akka.actor.AbstractLoggingActor;
import akka.actor.Props;
import akka.persistence.cassandra.query.javadsl.CassandraReadJournal;
import akka.persistence.query.PersistenceQuery;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Source;

class ReadSideProcessorIdsActor extends AbstractLoggingActor {
    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .build();
    }

    @Override
    public void preStart() {
        log().info("Start");
        runPullJournalStream();
    }

    private void runPullJournalStream() {
        ActorMaterializer materializer = ActorMaterializer.create(context().system());

        CassandraReadJournal cassandraReadJournal =
                PersistenceQuery.get(context().system()).getReadJournalFor(CassandraReadJournal.class, CassandraReadJournal.Identifier());

        Source<String, NotUsed> source = cassandraReadJournal.persistenceIds(); // TODO offset?
        source.runForeach(this::handleId, materializer);
    }

    private void handleId(String id) {
        log().info("Id {}", id);
    }

    @Override
    public void postStop() {
        log().info("Stop");
    }

    static Props props() {
        return Props.create(ReadSideProcessorIdsActor.class);
    }
}
