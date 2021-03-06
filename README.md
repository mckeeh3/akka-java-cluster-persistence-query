## Akka Java Cluster Persistence Query Example

### Introduction

This is a Java, Maven, Akka project that demonstrates how to setup an
[Akka Cluster](https://doc.akka.io/docs/akka/current/index-cluster.html)
with an example implementation of
[Akka Persistence Query](https://doc.akka.io/docs/akka/current/persistence-query.html).

This project is one in a series of projects that starts with a simple Akka Cluster project and progressively builds up to examples of event sourcing and command query responsibility segregation.

The project series is composed of the following projects:
* [akka-java-cluster](https://github.com/mckeeh3/akka-java-cluster)
* [akka-java-cluster-aware](https://github.com/mckeeh3/akka-java-cluster-aware)
* [akka-java-cluster-singleton](https://github.com/mckeeh3/akka-java-cluster-singleton)
* [akka-java-cluster-sharding](https://github.com/mckeeh3/akka-java-cluster-sharding)
* [akka-java-cluster-persistence](https://github.com/mckeeh3/akka-java-cluster-persistence)
* [akka-java-cluster-persistence-query](https://github.com/mckeeh3/akka-java-cluster-persistence-query) (this project)

Each project can be cloned, built, and runs independently of the other projects.

This project contains an example implementation of
[Akka persistence](https://doc.akka.io/docs/akka/current/persistence.html)
and
[Akka Persistence Query]((https://doc.akka.io/docs/akka/current/persistence-query.html)).
Here we will focus on the implementation details in this project. Please see the
[Akka documentation](https://doc.akka.io/docs/akka/current/persistence-query.html)
for a more detailed discussion about Akka Persistence Query.

### An Example Implementation of Akka Persistence Query

This project builds on the
[akka-java-cluster-persistence](https://github.com/mckeeh3/akka-java-cluster-persistence)
project, which build on the
[akka-java-cluster-sharding](https://github.com/mckeeh3/akka-java-cluster-sharding)
project. While the prior akka-java-cluster-persistence project focused on the creation and persistence of events, this project includes both the creation of events, the write-side of
[CQRS](https://martinfowler.com/bliki/CQRS.html),
and it consists of the propagation of events to the read-side of CQRS.

Follow the code in the `Runner` class, and there you will see how the write-side and the read-side processes are started. The write-side in this project is a clone of the write-side in the akka-java-cluster-persistence project. In addition to the write-side classes, this project includes a set of what are called read-side processor classes.

The read-side processing flow is driven through the use of a cluster singleton and cluster sharding. Let's walk through the code.

~~~java
private static void startupReadSide(ActorSystem actorSystem) {
    createReadSideClusterSingletonManagerActor(actorSystem);
}
~~~

The `Runner` class starts the read-side by invoking the `startupReadSide(actorSystem)` method, which in turn invokes the `createReadSideClusterSingletonManagerActor(actorSystem)` method.

~~~java
private static void createReadSideClusterSingletonManagerActor(ActorSystem actorSystem) {
    ClusterSingletonManagerSettings settings = ClusterSingletonManagerSettings.create(actorSystem);
    Props clusterSingletonManagerProps = ClusterSingletonManager.props(
            ReadSideProcessorHeartbeatSingletonActor.props(setupReadSideClusterSharding(actorSystem)),
            PoisonPill.getInstance(),
            settings
    );

    actorSystem.actorOf(clusterSingletonManagerProps, "clusterSingletonManager");
}
~~~

Note that this cluster singleton actor is the `ReadSideProcessorHeartbeatSingletonActor` class. This singleton actor is passed a cluster shard region actor reference, which is created in the `setupReadSideClusterSharding(actorSystem)` method.

~~~java
private static ActorRef setupReadSideClusterSharding(ActorSystem actorSystem) {
    ClusterShardingSettings settings = ClusterShardingSettings.create(actorSystem);
    return ClusterSharding.get(actorSystem).start(
            "readSideProcessor",
            ReadSideProcessorActor.props(),
            settings,
            ReadSideProcessorActor.messageExtractor()
    );
}
~~~

In this example implementation, the read-side processing flow relies on a cluster singleton actor to bootstrap and keep the read-side processing actors running.

~~~java
private void heartbeat() {
    log().info("Heartbeat {}", ReadSideProcessorActor.Tag.tags());
    ReadSideProcessorActor.Tag.tags().forEach(tag -> shardRegion.tell(tag, self()));
}
~~~

The cluster singleton actor `ReadSideProcessorHeartbeatSingletonActor` schedules a heartbeat interval message. On each heartbeat, the singleton actor sends a message to each `ReadSideProcessorActor` instance. This message is composed of a tag. So what is a tag?

#### A brief overview of event Tags

You may recall that as events are persisted they were tagged.

~~~java
private void deposit(EntityMessage.DepositCommand depositCommand) {
    log.info("{} <- {}", depositCommand, sender());
    persist(tagCommand(depositCommand), taggedEvent -> handleDeposit(depositCommand, taggedEvent));
}
~~~

Note that in the call to the `persist` method in the `EntityPersistenceActor` class, the `tagCommand` method is invoked.

~~~java
private static Tagged tagCommand(EntityMessage.DepositCommand depositCommand) {
    return new Tagged(new EntityMessage.DepositEvent(depositCommand), EntityMessage.eventTag(depositCommand));
}
~~~

The `tagCommand` method returns a `Tagged` object, which is an Akka persistence tagged event object. Tags are used to partition events into groups. These tagged groups are used to allow for processing events from the write-side to the read-side in parallel. The goal here is to run in parallel multiple read-side processes that are each reading tagged events that are created and persisted on the write-side. Each read-side processor handles all of the events for a specific tag. As we will see, the read-side processors each start a stream of events from the write-side. These write-side events are then used to update read-side views of the data. In this example, we will not be writing the events to a read-side database. The last step of updating the read-side views is very application specific, so this part is not implemented.

This parallel processing is often used when the persisting of events on the write-side is much faster than a single serial process of moving events to the read-side. Tags are used to start multiple concurrent read-side processors. See the documentations
[EventsByPersistenceIdQuery and CurrentEventsByPersistenceIdQuery](https://doc.akka.io/docs/akka/current/persistence-query.html#eventsbypersistenceidquery-and-currenteventsbypersistenceidquery)
for more details.

#### Now back to parallel read-side processing

As previously discussed, a cluster singleton actor uses a heartbeat to trigger sending tag messages to a shard region actor. The shard region actor forwards each tag message to a `ReadSideProcessorActor`. Each `ReadSideProcessorActor` instance creates an instance of a `ReadSideProcessorEventTagActor`, which is created using what is called a backoff supervisor.

~~~java
private void heartbeat(Tag tag) {
    log().info("Heartbeat {}", tag);

    if (readSideProcessorEventTag == null) {
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
~~~

The `heartbeat` method in the `ReadSideProcessorActor` creates a backoff supervisor for the `ReadSideProcessorEventTagActor`. The reason for using a backoff supervisor is to provide a way to handle problems dealing with failures that occur while accessing external databases. In this example the `ReadSideProcessorEventTagActor` reads events from the write-side database. When the database is unavailable for some reason, this actor will fail and throw an exception. When an external service, such as a database, is unavailable often circuit breakers and retry loops are used to gracefully recover from these failures. This is exactly what a backoff supervisor provides. See the Akka documentation [Delayed restarts with the BackoffSupervisor pattern](https://doc.akka.io/docs/akka/current/general/supervision.html#delayed-restarts-with-the-backoffsupervisor-pattern) for more details.

~~~java
private CompletionStage<List<Row>> readTagOffset(Materializer materializer) {
    PreparedStatement preparedStatement = session.prepare(String.format("SELECT offset FROM %s.tag_read_progress WHERE tag = ?", keyspaceName));
    return CassandraSource.create(preparedStatement.bind(tag.value), session).runWith(Sink.seq(), materializer);
}
~~~


The `ReadSideProcessorEventTagActor` class does the actual reading from the write-side event store. This class uses a custom Cassandra table to store offsets by tag. Each time an instance of this actor is started it first recovers the offset of the last successfully read event. This offset is then used to resume reading event from that offset point on.

~~~java
private void readEventsByTag(List<Row> rows) {
    if (rows.size() > 0) {
        readEventsByTag(rows.get(0).getUUID("offset"));
    } else {
        readEventsByTag(Offset.noOffset());
    }
}
~~~

The results of the offset query are examined in the `readEventsByTag` method. The method handles the case when no offset has yet been stored for a given tag.

~~~java
private void readEventsByTag(UUID uuid) {
    readEventsByTag(Offset.timeBasedUUID(uuid));
}
~~~

The `readEventsByTag(UUID uuid)` converts the retrieved offset from a `UUID` to an Akka persistence `Offset` type.

~~~java
private void readEventsByTag(Offset offset) {
    log().info("Read {} from offset {}", tag, offset);
    CassandraReadJournal cassandraReadJournal =
            PersistenceQuery.get(context().system()).getReadJournalFor(CassandraReadJournal.class, CassandraReadJournal.Identifier());

    cassandraReadJournal.eventsByTag(tag.value, offset).runForeach(this::handleEvent, actorMaterializer);
}
~~~

The `readEventsByTag` method creates and Akka stream of events. The stream is created using the [Akka Persistence Cassandra](https://doc.akka.io/docs/akka-persistence-cassandra/current/index.html) [Events by Tag](https://doc.akka.io/docs/akka-persistence-cassandra/current/events-by-tag.html). Each streamed event is passed to the `handleReadSideEvent` method.

~~~java
private void handleReadSideEvent(EventEnvelope eventEnvelope) {
  log().info("Read-side {}", eventEnvelope);

  // TODO These events are stored in a read-side database.
  // To keep things simple storing events to a read-side database is not implemented.

  // todo add something to do updates every Nth event
  updateTagOffset(eventEnvelope.offset());
}

private void updateTagOffset(Offset offset) {
    CassandraSource.create(preparedUpdateStatement.bind(((TimeBasedUUID) offset).value(), tag.value), session).runWith(Sink.seq(), actorMaterializer)
            .exceptionally(t -> {
                throw new RuntimeException(String.format("Update tag_read_progress, %s failed!", tag), t);
            });
}
~~~

The streamed events are passed to the `headleReadSideEvent` method. Here two things need to be handled. The main task is to use the event to update a read-side database. This activity is very application specific. As mentioned in the comments, this was not implemented to keep things simple.

Also, note that the event offset is stored using the `updateTagOffset` method. In this implementation, the offset is stored for each event. A possible optimization would be to store the offset less frequently.

We've completed the tour through the read-side implementation in this example project. This read-side processor implementation is built using everything that was covered in the prior five example projects. The write-side and the read-side are running in an Akka cluster, which was introduced in the first example project [akka-java-cluster](https://github.com/mckeeh3/akka-java-cluster). While the example code did not directly use any cluster-aware actors, covered in the [akka-java-cluster-aware](https://github.com/mckeeh3/akka-java-cluster-aware) project, this type of actor is used to implement cluster singleton actors, and it is heavily used internally with cluster sharding.

A cluster singleton, covered in the [akka-java-cluster-singleton](https://github.com/mckeeh3/akka-java-cluster-singleton) project, is used to bootstrap the read-side actors. The read-side is implemented using cluster sharding, which was covered in the [akka-java-cluster-sharding](https://github.com/mckeeh3/akka-java-cluster-sharding) project. Cluster sharding is used to run parallel read-side processor actors that process events by tag.

There is a somewhat subtle feature of the implementation of the cluster singleton and cluster sharding in this project. The cluster singleton is continually sending messages to the read-side tag processor actors. The subtle reason for doing this is to make sure that each read-side processor is running and they continue to run as cluster nodes are added and removed from the cluster.

Let's walk through a simple example scenario. Say there are five tags so therefore we need to run five read-side processor actors, one for each tag. Say the cluster is running with three nodes and the five read-side processor actors are distributed across the cluster. Two actors on node one, one of node two, and two on node three.

What happens when one of the cluster nodes is removed?  Well, cluster sharding knows how to redistribute the read-side processor actors across the remaining two cluster nodes. However, cluster sharding does not restart the sharded actors. Something else needs to trigger the actor restart. This is where our cluster singleton actor comes in. On the next heartbeat, the cluster singleton actor sends messages to each read-side processor actor. These messages are sent to a cluster region actor. If an instance of the target actor is not available, the cluster sharding actors will create an instance. The result in this example scenario is that the read-side processor actor that was running on the now gone node two is now restarted on one of the remaining nodes.

### Installation

~~~bash
git clone https://github.com/mckeeh3/akka-java-cluster-persistence.git
cd akka-java-cluster-persistence
mvn clean package
~~~

The Maven command builds the project and creates a self contained runnable JAR.

### Cassandra Installation and Running

For Cassandra installation please see the [Installing Cassandra](http://cassandra.apache.org/doc/latest/getting_started/installing.html) documentation.

One of the easiest ways to use Cassandra for testing is to download the tar file, uncompress the files, and run a Cassandra process in the background.

~~~bash
tar -xzvf apache-cassandra-3.6-bin.tar.gz
cd apache-cassandra-3.6
./bin cassandra -f
~~~

This installs a ready to tun version of Cassandra.

Note: please make sure to use Java 8 to run Cassandra.

Tip: the default location of the database files is `data` directory within the Cassandra installation directory. To reset with an empty database stop Cassandra, remove the `data` directory and restart Cassandra.

### Run a cluster (Mac, Linux)

The project contains a set of scripts that can be used to start and stop individual cluster nodes or start and stop a cluster of nodes.

The main script `./akka` is provided to run a cluster of nodes or start and stop individual nodes.
Use `./akka node start [1-9] | stop` to start and stop individual nodes and `./akka cluster start [1-9] | stop` to start and stop a cluster of nodes.
The `cluster` and `node` start options will start Akka nodes on ports 2551 through 2559.
Both `stdin` and `stderr` output is sent to a file in the `/tmp` directory using the file naming convention `/tmp/<project-dir-name>-N.log`.

Start node 1 on port 2551 and node 2 on port 2552.
~~~bash
./akka node start 1
./akka node start 2
~~~

Stop node 3 on port 2553.
~~~bash
./akka node stop 3
~~~

Start a cluster of four nodes on ports 2551, 2552, 2553, and 2554.
~~~bash
./akka cluster start 4
~~~

Stop all currently running cluster nodes.
~~~bash
./akka cluster stop
~~~

You can use the `./akka cluster start [1-9]` script to start multiple nodes and then use `./akka node start [1-9]` and `./akka node stop [1-9]`
to start and stop individual nodes.

Use the `./akka node tail [1-9]` command to `tail -f` a log file for nodes 1 through 9.

The `./akka cluster status` command displays the status of a currently running cluster in JSON format using the
[Akka Management](https://developer.lightbend.com/docs/akka-management/current/index.html)
extension
[Cluster Http Management](https://developer.lightbend.com/docs/akka-management/current/cluster-http-management.html).

### Run a cluster (Windows, command line)

The following Maven command runs a signle JVM with 3 Akka actor systems on ports 2551, 2552, and a radmonly selected port.
~~~~bash
mvn exec:java
~~~~
Use CTRL-C to stop.

To run on specific ports use the following `-D` option for passing in command line arguements.
~~~~bash
mvn exec:java -Dexec.args="2551"
~~~~
The default no arguments is equilevalant to the following.
~~~~bash
mvn exec:java -Dexec.args="2551 2552 0"
~~~~
A common way to run tests is to start single JVMs in multiple command windows. This simulates running a multi-node Akka cluster.
For example, run the following 4 commands in 4 command windows.
~~~~bash
mvn exec:java -Dexec.args="2551" > /tmp/$(basename $PWD)-1.log
~~~~
~~~~bash
mvn exec:java -Dexec.args="2552" > /tmp/$(basename $PWD)-2.log
~~~~
~~~~bash
mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-3.log
~~~~
~~~~bash
mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-4.log
~~~~
This runs a 4 node Akka cluster starting 2 nodes on ports 2551 and 2552, which are the cluster seed nodes as configured and the `application.conf` file.
And 2 nodes on randomly selected port numbers.
The optional redirect `> /tmp/$(basename $PWD)-4.log` is an example for pushing the log output to filenames based on the project direcctory name.

For convenience, in a Linux command shell define the following aliases.

~~~~bash
alias p1='cd ~/akka-java/akka-java-cluster'
alias p2='cd ~/akka-java/akka-java-cluster-aware'
alias p3='cd ~/akka-java/akka-java-cluster-singleton'
alias p4='cd ~/akka-java/akka-java-cluster-sharding'
alias p5='cd ~/akka-java/akka-java-cluster-persistence'
alias p6='cd ~/akka-java/akka-java-cluster-persistence-query'

alias m1='clear ; mvn exec:java -Dexec.args="2551" > /tmp/$(basename $PWD)-1.log'
alias m2='clear ; mvn exec:java -Dexec.args="2552" > /tmp/$(basename $PWD)-2.log'
alias m3='clear ; mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-3.log'
alias m4='clear ; mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-4.log'
~~~~

The p1-6 alias commands are shortcuts for cd'ing into one of the six project directories.
The m1-4 alias commands start and Akka node with the appropriate port. Stdout is also redirected to the /tmp directory.
