package cluster.persistence.query;

import akka.NotUsed;
import akka.actor.AbstractLoggingActor;
import akka.actor.Props;
import akka.persistence.cassandra.query.javadsl.CassandraReadJournal;
import akka.persistence.query.EventEnvelope;
import akka.persistence.query.Offset;
import akka.persistence.query.PersistenceQuery;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Source;

class PullTaggedJournalEventsActor extends AbstractLoggingActor {
    private final String tag;

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

        runPullJournalStream();
    }

    private void runPullJournalStream() {
        ActorMaterializer materializer = ActorMaterializer.create(getContext().getSystem());

        CassandraReadJournal cassandraReadJournal =
                PersistenceQuery.get(getContext().getSystem()).getReadJournalFor(CassandraReadJournal.class, CassandraReadJournal.Identifier());

        Source<EventEnvelope, NotUsed> source = cassandraReadJournal.eventsByTag(tag, Offset.noOffset()); // TODO offset?
        source.runForeach(this::handleEvent, materializer);
    }

    private void handleEvent(EventEnvelope eventEnvelope) {
        getContext().getParent().tell(eventEnvelope.event(), getSelf());
    }

    @Override
    public void postStop() {
        log().info("Stop for tag {}", tag);
    }

    static Props props(String tag) {
        return Props.create(PullTaggedJournalEventsActor.class, tag);
    }
}
