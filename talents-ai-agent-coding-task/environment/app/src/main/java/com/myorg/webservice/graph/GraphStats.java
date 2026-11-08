package com.myorg.webservice.graph;

/**
 * Read-only statistics over the graph stored by {@link GraphDb}, for monitoring.
 *
 * <p>Implementations: {@link ArangoStatsAdapter} (AQL), {@link Neo4jStatsAdapter} (Cypher) and
 * {@link JanusGraphStatsAdapter} (Gremlin). {@link GraphStatsFactory#create} creates the one for a database type; it
 * must be the same type as the {@link GraphDb} whose data it reads.
 */
public interface GraphStats extends AutoCloseable {

    /** Connects to the database, retrying while it is not ready yet. */
    void connect();

    /** Number of vertices. */
    long vertexCount();

    /** Number of edges. */
    long edgeCount();

    /**
     * Number of edges leaving a vertex.
     *
     * @param id vertex ID
     * @return the number of outbound edges, or 0 if there is no such vertex
     */
    long outDegree(String id);

    @Override
    void close();
}
