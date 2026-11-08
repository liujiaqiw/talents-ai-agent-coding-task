package com.myorg.webservice.harbor;

import static org.assertj.core.api.Assertions.assertThat;

import com.myorg.webservice.graph.GraphDatabaseFactory;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Finds the database families of the refactored graph package. The agent chooses the names of the families and their
 * classes, so a family's concrete factory is found by the constructor the instruction specifies for its database:
 * ArangoDB takes 4 strings, Neo4j 3 and JanusGraph 1.
 */
final class HarborFamilies {

    static final String GRAPH = "com.myorg.webservice.graph";
    static final int ARANGO = 4;
    static final int NEO4J = 3;
    static final int JANUSGRAPH = 1;

    /** Classes of the drivers' connection objects, of which a factory may hold exactly one. */
    private static final Set<String> DRIVER_TYPES = Set.of("com.arangodb.ArangoDB", "org.neo4j.driver.Driver",
            "org.apache.tinkerpop.gremlin.driver.Cluster");

    private HarborFamilies() {
    }

    /** The concrete factory of the database whose factory constructor takes {@code parameterCount} strings. */
    static Class<?> factoryClass(int parameterCount) throws IOException, URISyntaxException {
        List<Class<?>> factories = new ArrayList<>();
        for (Class<?> type : graphClasses()) {
            if (GraphDatabaseFactory.class.isAssignableFrom(type) && !type.isInterface()
                    && !Modifier.isAbstract(type.getModifiers()) && stringConstructor(type, parameterCount) != null) {
                factories.add(type);
            }
        }
        assertThat(factories)
                .as("exactly one concrete GraphDatabaseFactory with a public constructor of %d String parameters",
                        parameterCount)
                .hasSize(1);
        return factories.get(0);
    }

    /** A new factory of the database whose factory constructor takes as many strings as given. */
    static GraphDatabaseFactory newFactory(String... arguments) throws Exception {
        Constructor<?> constructor = stringConstructor(factoryClass(arguments.length), arguments.length);
        return (GraphDatabaseFactory) constructor.newInstance((Object[]) arguments);
    }

    /** The subpackage of graph/ that holds a class, or null for a class directly in graph/. */
    static String familyPackage(Class<?> type) {
        String packageName = type.getPackageName();
        return packageName.startsWith(GRAPH + ".") ? packageName : null;
    }

    /** All instances of driver connection classes reachable from the given objects through the graph package. */
    static Set<Object> drivers(Object... roots) {
        Set<Object> drivers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object value : reachable(roots)) {
            if (isDriver(value.getClass())) {
                drivers.add(value);
            }
        }
        return drivers;
    }

    /** The string values reachable from the given objects through the graph package. */
    static List<String> strings(Object... roots) {
        return reachable(roots).stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static Constructor<?> stringConstructor(Class<?> type, int parameterCount) {
        Class<?>[] parameterTypes = new Class<?>[parameterCount];
        Arrays.fill(parameterTypes, String.class);
        try {
            Constructor<?> constructor = type.getConstructor(parameterTypes);
            return constructor;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static List<Class<?>> graphClasses() throws IOException, URISyntaxException {
        Path classes = Path.of(GraphDatabaseFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path graph = classes.resolve(GRAPH.replace('.', '/'));
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> files = Files.walk(graph)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                try {
                    types.add(Class.forName(name, false, HarborFamilies.class.getClassLoader()));
                } catch (ClassNotFoundException | LinkageError e) {
                    // not loadable on its own, e.g. a synthetic class; it cannot be a factory
                }
            }
        }
        return types;
    }

    /**
     * Walks the fields of the objects of the graph package, starting from the given roots, and returns every value
     * found, including the contents of arrays, collections, maps, optionals and atomic references.
     */
    private static List<Object> reachable(Object... roots) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> pending = new ArrayDeque<>();
        List<Object> values = new ArrayList<>();
        pushAll(pending, Arrays.asList(roots));
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            if (!seen.add(value)) {
                continue;
            }
            values.add(value);
            if (value instanceof Object[] array) {
                pushAll(pending, Arrays.asList(array));
            } else if (value instanceof Collection<?> collection) {
                pushAll(pending, collection);
            } else if (value instanceof Map<?, ?> map) {
                pushAll(pending, map.values());
            } else if (value instanceof Optional<?> optional) {
                pushAll(pending, optional.stream().toList());
            } else if (value instanceof AtomicReference<?> reference) {
                pushAll(pending, Collections.singletonList(reference.get()));
            } else if (value.getClass().getName().startsWith(GRAPH + ".")) {
                pushAll(pending, fieldValues(value));
            }
        }
        return values;
    }

    /** Queues the non-null values; an ArrayDeque cannot hold null. */
    private static void pushAll(Deque<Object> pending, Collection<?> values) {
        for (Object value : values) {
            if (value != null) {
                pending.push(value);
            }
        }
    }

    private static List<Object> fieldValues(Object object) {
        List<Object> values = new ArrayList<>();
        for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    values.add(field.get(object));
                } catch (RuntimeException | IllegalAccessException e) {
                    // an inaccessible field cannot be inspected
                }
            }
        }
        return values;
    }

    private static boolean isDriver(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (DRIVER_TYPES.contains(current.getName())) {
                return true;
            }
            for (Class<?> implemented : current.getInterfaces()) {
                if (isDriver(implemented)) {
                    return true;
                }
            }
        }
        return false;
    }
}
