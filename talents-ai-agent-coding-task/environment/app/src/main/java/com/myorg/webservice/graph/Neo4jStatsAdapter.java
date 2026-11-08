package com.myorg.webservice.graph;

import java.time.Duration;
import java.util.Map;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

/** Neo4j implementation of {@link GraphStats}, over the nodes and relationships of {@link Neo4jAdapter}. */
public class Neo4jStatsAdapter implements GraphStats {

    private static final System.Logger LOG = System.getLogger(Neo4jStatsAdapter.class.getName());

    private final String uri;
    private final String user;
    private final String password;
    private Driver driver;

    public Neo4jStatsAdapter(String uri, String user, String password) {
        this.uri = uri;
        this.user = user;
        this.password = password;
    }

    @Override
    public void connect() {
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                driver.verifyConnectivity();
                return;
            } catch (RuntimeException e) {
                last = e;
                LOG.log(System.Logger.Level.WARNING, "Failed to connect to Neo4j (attempt {0}): {1}", attempt,
                        e.getMessage());
                sleep(Duration.ofSeconds(2));
            }
        }
        driver.close();
        driver = null;
        throw new IllegalStateException("Cannot connect to Neo4j at " + uri, last);
    }

    @Override
    public long vertexCount() {
        return count("MATCH (v:Vertex) RETURN count(v) AS n", Map.of());
    }

    @Override
    public long edgeCount() {
        return count("MATCH (:Vertex)-[e:EDGE]->(:Vertex) RETURN count(e) AS n", Map.of());
    }

    @Override
    public long outDegree(String id) {
        return count("MATCH (v:Vertex {vertexId: $id})-[e:EDGE]->() RETURN count(e) AS n", Map.of("id", id));
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    private long count(String query, Map<String, Object> parameters) {
        return driver.executableQuery(query).withParameters(parameters).execute()
                .records().get(0).get("n").asLong();
    }

    private static void sleep(Duration interval) {
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
