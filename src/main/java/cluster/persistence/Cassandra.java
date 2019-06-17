package cluster.persistence;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueType;

import java.util.ArrayList;
import java.util.List;

class Cassandra {
    static Session session() {
        Cluster.Builder builder = Cluster.builder();
        Config.contactPoints().forEach(builder::addContactPoint);
        builder.withPort(Config.port());
        return builder.build().connect(); //Config.keyspace());
    }

    private static class Config {
        static List<String> contactPoints() {
            List<String> contactPoints = new ArrayList<>();

            ConfigFactory.load().getList("cassandra-journal.contact-points")
                    .forEach(value -> {
                        if (value.valueType().equals(ConfigValueType.STRING)) {
                            contactPoints.add((String) value.unwrapped());
                        }
                    });

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
