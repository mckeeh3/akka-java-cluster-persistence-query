package cluster.persistence.query;

import akka.actor.AbstractLoggingActor;
import akka.actor.Props;
import cluster.persistence.EntityMessage;

class PullJournalEventsActor extends AbstractLoggingActor {
    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(EntityMessage.EntityEvent.class, this::entityEvent)
                .build();
    }

    private void entityEvent(EntityMessage.EntityEvent entityEvent) {
        log().info("{} <- {}", entityEvent, sender().path());
    }

    @Override
    public void preStart() {
        log().info("Start");

        for (int i = 0; i <= EntityMessage.numberOfEventTags; i++) {
            context().actorOf(ReadSideProcessorEventTagsActor.props("" + i), String.format("pullTaggedJournalEvents-%d", i));
        }
    }

    @Override
    public void postStop() {
        log().info("Stop");
    }

    static Props props() {
        return Props.create(PullJournalEventsActor.class);
    }
}
