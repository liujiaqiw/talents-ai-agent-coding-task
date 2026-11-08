package com.myorg.webservice.graph;

import java.util.Locale;

/** Creates the {@link GraphDb} implementation of a database type. */
public final class GraphDbFactory {

    private GraphDbFactory() {
    }

    /**
     * Creates an unconnected instance; the caller is responsible for {@link GraphDb#connect()} and
     * {@link GraphDb#close()}.
     *
     * @param type     {@code arangodb}, {@code neo4j} or {@code janusgraph} (case-insensitive)
     * @param url      server URL (the Bolt URI for Neo4j)
     * @param user     user name; ignored by JanusGraph
     * @param password password; ignored by JanusGraph
     * @return the implementation for {@code type}; ArangoDB always uses the database named {@code graph}
     * @throws IllegalArgumentException if {@code type} is not supported
     */
    public static GraphDb create(String type, String url, String user, String password) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "arangodb" -> new ArangoDbAdapter(url, user, password, "graph");
            case "neo4j" -> new Neo4jAdapter(url, user, password);
            case "janusgraph" -> new JanusGraphAdapter(url);
            default -> throw new IllegalArgumentException("Unsupported graph database type: " + type);
        };
    }
}
