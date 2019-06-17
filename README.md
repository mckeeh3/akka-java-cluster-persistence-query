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

### What is Akka Persistence Query

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

In this example implementation, the read-side processing flow relies on a cluster singleton actor to bootstrap and monitor the read-side processing actors.

~~~java
private void heartbeat() {
    log().info("Heartbeat {}", ReadSideProcessorActor.Tag.tags());
    ReadSideProcessorActor.Tag.tags().forEach(tag -> shardRegion.tell(tag, self()));
}
~~~

The cluster singleton actor `ReadSideProcessorHeartbeatSingletonActor` schedules a heartbeat interval message. On each heartbeat, the singleton actor sends a message to a `ReadSideProcessorActor` instance. This message is composed of a tag. So what is a tag?

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

The `tagCommand` method returns a `Tagged` object, which is an Akka persistence tagged event object. Tags are used to partition events into groups. These tagged groups are used to allow for processing events from the write-side to the read-side in parallel.

This parallel processing is often used when the persisting of events on the write-side is much faster than a single serial process of moving events to the read-side. Tags are used to start multiple concurrent read-side processors. See the documentations
[EventsByPersistenceIdQuery and CurrentEventsByPersistenceIdQuery](https://doc.akka.io/docs/akka/current/persistence-query.html#eventsbypersistenceidquery-and-currenteventsbypersistenceidquery)
for more details.




TODO

### Installation

~~~bash
git clone https://github.com/mckeeh3/akka-java-cluster-persistence.git
cd akka-java-cluster-persistence
mvn clean package
~~~

The Maven command builds the project and creates a self contained runnable JAR.

### Cassandra Installation

TODO make sure to mention creating all of the tables

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
