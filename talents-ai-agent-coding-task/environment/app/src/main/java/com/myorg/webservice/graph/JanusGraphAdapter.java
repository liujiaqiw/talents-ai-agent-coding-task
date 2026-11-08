package com.myorg.webservice.graph;

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

/**
 * JanusGraph implementation of {@link GraphDb}, talking to a JanusGraph (Gremlin) server.
 *
 * <p>Vertices have the label {@value #VERTEX} and their ID in the property {@value #VERTEX_ID}; edges have the label
 * {@value #EDGE}. Queries are Gremlin traversals sent to the server's {@code g} traversal source.
 */
public class JanusGraphAdapter implements GraphDb {

    static final String VERTEX = "vertex";
    /** Not "edge", which JanusGraph reserves. */
    static final String EDGE = "link";
    static final String VERTEX_ID = "vertexId";

    private static final System.Logger LOG = System.getLogger(JanusGraphAdapter.class.getName());

    private final String url;
    private Cluster cluster;
    private GraphTraversalSource g;

    public JanusGraphAdapter(String url) {
        this.url = url;
    }

    @Override
    public void connect() {
        connect(30, Duration.ofSeconds(2));
    }

    /**
     * Connects to the Gremlin server.
     *
     * @param attempts number of attempts; the server may still be starting
     * @param interval pause between attempts
     * @throws IllegalStateException if the server cannot be reached
     */
    public void connect(int attempts, Duration interval) {
        URI uri = URI.create(url);
        cluster = Cluster.build(uri.getHost())
                .port(uri.getPort() > 0 ? uri.getPort() : 8182)
                .path(uri.getPath() == null || uri.getPath().isEmpty() ? "/gremlin" : uri.getPath())
                .enableSsl("wss".equalsIgnoreCase(uri.getScheme()))
                .create();
        g = traversal().withRemote(DriverRemoteConnection.using(cluster, "g"));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                g.inject(1).next();
                LOG.log(System.Logger.Level.INFO, "Connected to JanusGraph {0}", url);
                return;
            } catch (RuntimeException e) {
                last = e;
                LOG.log(System.Logger.Level.WARNING, "Failed to connect to JanusGraph (attempt {0}): {1}", attempt,
                        e.getMessage());
                sleep(interval);
            }
        }
        close();
        throw new IllegalStateException("Cannot connect to JanusGraph at " + url, last);
    }

    /**
     * Nothing to create: with its default schema settings, JanusGraph creates the vertex and edge labels and the
     * property keys the first time they are used.
     */
    @Override
    public void ensureSchema() {
    }

    @Override
    public void upsertVertex(String id, Map<String, Object> properties) {
        GraphTraversal<Vertex, Vertex> vertex = g.V().has(VERTEX, VERTEX_ID, id).fold()
                .coalesce(__.<Vertex>unfold(), __.<List<Vertex>>addV(VERTEX).property(VERTEX_ID, id));
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            vertex = vertex.property(VertexProperty.Cardinality.single, property.getKey(), property.getValue());
        }
        vertex.iterate();
    }

    @Override
    public void upsertEdge(String fromId, String toId, Map<String, Object> properties) {
        GraphTraversal<Vertex, Edge> edge = g.V().has(VERTEX, VERTEX_ID, fromId).as("from")
                .V().has(VERTEX, VERTEX_ID, toId)
                .coalesce(__.<Vertex>inE(EDGE).where(__.outV().as("from")), __.<Vertex>addE(EDGE).from("from"));
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            edge = edge.property(property.getKey(), property.getValue());
        }
        edge.iterate();
    }

    @Override
    public Optional<Map<String, Object>> findVertex(String id) {
        return g.V().has(VERTEX, VERTEX_ID, id).valueMap().tryNext().map(values -> {
            Map<String, Object> properties = new HashMap<>();
            values.forEach((key, value) -> properties.put(String.valueOf(key),
                    value instanceof List<?> list && list.size() == 1 ? list.get(0) : value));
            properties.remove(VERTEX_ID);
            return properties;
        });
    }

    /** Breadth-first, one level per query, so that the result is ordered by hops and then by ID. */
    @Override
    public List<String> neighbors(String id, int depth) {
        Set<String> visited = new HashSet<>(Set.of(id));
        List<String> frontier = List.of(id);
        List<String> result = new ArrayList<>();
        for (int hop = 1; hop <= depth && !frontier.isEmpty(); hop++) {
            List<String> level = g.V().has(VERTEX, VERTEX_ID, P.within(frontier)).out(EDGE)
                    .<String>values(VERTEX_ID).dedup().toList().stream()
                    .filter(visited::add)
                    .sorted()
                    .toList();
            result.addAll(level);
            frontier = level;
        }
        return result;
    }

    @Override
    public void close() {
        if (g != null) {
            try {
                g.close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Failed to close the JanusGraph traversal source", e);
            }
            g = null;
        }
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
    }

    private static void sleep(Duration interval) {
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
