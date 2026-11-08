package com.myorg.webservice.graph;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Graph database of the webservice: vertices identified by a string ID, directed edges between them, and
 * traversals.
 *
 * <p>Implementations: {@link ArangoDbAdapter} (AQL), {@link Neo4jAdapter} (Cypher) and {@link JanusGraphAdapter}
 * (Gremlin). {@link GraphDbFactory#create} creates the one for a database type.
 */
public interface GraphDb extends AutoCloseable {

    /** Connects to the database, retrying while it is not ready yet. */
    void connect();

    /** Creates the database objects (collections, graph definition) if they are missing; idempotent. */
    void ensureSchema();

    /**
     * Creates the vertex, or merges the given properties into it if it exists.
     *
     * @param id         vertex ID
     * @param properties vertex properties
     */
    void upsertVertex(String id, Map<String, Object> properties);

    /**
     * Creates the directed edge from one vertex to another, or merges the given properties into it if it exists.
     *
     * @param fromId     ID of the source vertex
     * @param toId       ID of the target vertex
     * @param properties edge properties
     */
    void upsertEdge(String fromId, String toId, Map<String, Object> properties);

    /**
     * Reads a vertex.
     *
     * @param id vertex ID
     * @return the properties set with {@link #upsertVertex} (without the ID or any database-internal field), or empty
     *         if there is no such vertex
     */
    Optional<Map<String, Object>> findVertex(String id);

    /**
     * Follows outbound edges breadth-first.
     *
     * @param id    ID of the start vertex
     * @param depth maximum number of hops
     * @return IDs of the vertices reachable within {@code depth} hops, excluding the start vertex, each once,
     *         ordered by the number of hops needed to reach them and then by ID; empty if {@code depth} is less
     *         than 1 or there is no such vertex
     */
    List<String> neighbors(String id, int depth);

    @Override
    void close();
}
