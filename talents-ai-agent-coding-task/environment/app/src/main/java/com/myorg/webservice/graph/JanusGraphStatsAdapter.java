package com.myorg.webservice.graph;

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal;

import java.net.URI;
import java.time.Duration;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

/** JanusGraph implementation of {@link GraphStats}, over the vertices and edges of {@link JanusGraphAdapter}. */
public class JanusGraphStatsAdapter implements GraphStats {

    private static final System.Logger LOG = System.getLogger(JanusGraphStatsAdapter.class.getName());

    private final String url;
    private Cluster cluster;
    private GraphTraversalSource g;

    public JanusGraphStatsAdapter(String url) {
        this.url = url;
    }

    @Override
    public void connect() {
        URI uri = URI.create(url);
        cluster = Cluster.build(uri.getHost())
                .port(uri.getPort() > 0 ? uri.getPort() : 8182)
                .path(uri.getPath() == null || uri.getPath().isEmpty() ? "/gremlin" : uri.getPath())
                .enableSsl("wss".equalsIgnoreCase(uri.getScheme()))
                .create();
        g = traversal().withRemote(DriverRemoteConnection.using(cluster, "g"));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                g.inject(1).next();
                return;
            } catch (RuntimeException e) {
                last = e;
                LOG.log(System.Logger.Level.WARNING, "Failed to connect to JanusGraph (attempt {0}): {1}", attempt,
                        e.getMessage());
                sleep(Duration.ofSeconds(2));
            }
        }
        close();
        throw new IllegalStateException("Cannot connect to JanusGraph at " + url, last);
    }

    @Override
    public long vertexCount() {
        return g.V().hasLabel(JanusGraphAdapter.VERTEX).count().next();
    }

    @Override
    public long edgeCount() {
        return g.E().hasLabel(JanusGraphAdapter.EDGE).count().next();
    }

    @Override
    public long outDegree(String id) {
        return g.V().has(JanusGraphAdapter.VERTEX, JanusGraphAdapter.VERTEX_ID, id).outE(JanusGraphAdapter.EDGE)
                .count().next();
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
