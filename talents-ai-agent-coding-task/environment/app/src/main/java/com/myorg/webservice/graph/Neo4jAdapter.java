package com.myorg.webservice.graph;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.EagerResult;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;

/**
 * Neo4j implementation of {@link GraphDb}.
 *
 * <p>Vertices are nodes labelled {@value #VERTEX} whose ID is the unique property {@value #VERTEX_ID}, and edges are
 * relationships of type {@value #EDGE}. Queries are written in Cypher.
 */
public class Neo4jAdapter implements GraphDb {

    static final String VERTEX = "Vertex";
    static final String EDGE = "EDGE";
    static final String VERTEX_ID = "vertexId";

    private static final System.Logger LOG = System.getLogger(Neo4jAdapter.class.getName());

    private final String uri;
    private final String user;
    private final String password;
    private Driver driver;

    public Neo4jAdapter(String uri, String user, String password) {
        this.uri = uri;
        this.user = user;
        this.password = password;
    }

    @Override
    public void connect() {
        connect(30, Duration.ofSeconds(2));
    }

    /**
     * Connects to the server.
     *
     * @param attempts number of attempts; the server may still be starting
     * @param interval pause between attempts
     * @throws IllegalStateException if the server cannot be reached
     */
    public void connect(int attempts, Duration interval) {
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                driver.verifyConnectivity();
                LOG.log(System.Logger.Level.INFO, "Connected to Neo4j {0}", uri);
                return;
            } catch (RuntimeException e) {
                last = e;
                LOG.log(System.Logger.Level.WARNING, "Failed to connect to Neo4j (attempt {0}): {1}", attempt,
                        e.getMessage());
                sleep(interval);
            }
        }
        driver.close();
        driver = null;
        throw new IllegalStateException("Cannot connect to Neo4j at " + uri, last);
    }

    @Override
    public void ensureSchema() {
        run("CREATE CONSTRAINT vertex_id IF NOT EXISTS FOR (v:Vertex) REQUIRE v.vertexId IS UNIQUE", Map.of());
    }

    @Override
    public void upsertVertex(String id, Map<String, Object> properties) {
        run("MERGE (v:Vertex {vertexId: $id}) SET v += $properties", Map.of("id", id, "properties", properties));
    }

    @Override
    public void upsertEdge(String fromId, String toId, Map<String, Object> properties) {
        run("""
                MATCH (from:Vertex {vertexId: $from}), (to:Vertex {vertexId: $to})
                MERGE (from)-[e:EDGE]->(to)
                SET e += $properties""", Map.of("from", fromId, "to", toId, "properties", properties));
    }

    @Override
    public Optional<Map<String, Object>> findVertex(String id) {
        List<Record> records = run("MATCH (v:Vertex {vertexId: $id}) RETURN properties(v) AS properties",
                Map.of("id", id)).records();
        if (records.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> properties = new HashMap<>(records.get(0).get("properties").asMap());
        properties.remove(VERTEX_ID);
        return Optional.of(properties);
    }

    @Override
    public List<String> neighbors(String id, int depth) {
        if (depth < 1) {
            return List.of();
        }
        // The upper bound of a variable-length pattern cannot be a parameter; depth is an int, so this is safe.
        String query = """
                MATCH path = (start:Vertex {vertexId: $id})-[:EDGE*1..%d]->(v:Vertex)
                WHERE v <> start
                WITH v, min(length(path)) AS hops
                RETURN v.vertexId AS id
                ORDER BY hops, id""".formatted(depth);
        return run(query, Map.of("id", id)).records().stream()
                .map(record -> record.get("id").asString())
                .toList();
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    private EagerResult run(String query, Map<String, Object> parameters) {
        return driver.executableQuery(query).withParameters(parameters).execute();
    }

    private static void sleep(Duration interval) {
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
