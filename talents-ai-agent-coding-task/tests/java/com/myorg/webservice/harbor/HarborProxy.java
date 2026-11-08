package com.myorg.webservice.harbor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Harbor verifier: a TCP proxy that a test puts between a factory and its database, to decide when the driver's
 * connections get through and to see which of them are still open.
 *
 * <p>Every connection the proxy accepts waits while the proxy {@link Mode#HOLD holds}, so a driver stays in its
 * handshake for as long as the test needs. Once the proxy {@link Mode#FORWARD forwards}, waiting and new connections
 * are joined to the upstream server; once it {@link Mode#DROP drops}, they are closed. A connection counts as open
 * until the driver closes it (or the proxy drops it), so after a factory is closed {@link #openConnections()} shows
 * every connection a driver leaked.
 */
final class HarborProxy implements AutoCloseable {

    /** What the proxy does with the connections it accepts. */
    enum Mode { HOLD, FORWARD, DROP }

    private final InetSocketAddress upstream;
    private final ServerSocket server;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final AtomicInteger accepted = new AtomicInteger();
    private Mode mode;

    /**
     * Starts the proxy on a free local port.
     *
     * @param upstream the server to forward to, or {@code null} if there is none ({@link Mode#FORWARD} is then not
     *                 allowed)
     * @param mode     what to do with the first connections
     */
    HarborProxy(InetSocketAddress upstream, Mode mode) throws IOException {
        this.upstream = upstream;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        setMode(mode);
        Thread acceptor = new Thread(this::acceptLoop, "harbor-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** {@code host:port} of the proxy. */
    String address() {
        return "127.0.0.1:" + server.getLocalPort();
    }

    synchronized void setMode(Mode newMode) {
        if (newMode == Mode.FORWARD && upstream == null) {
            throw new IllegalArgumentException("the proxy has no upstream server to forward to");
        }
        mode = newMode;
        notifyAll();
    }

    /** Number of connections accepted so far. */
    int acceptedConnections() {
        return accepted.get();
    }

    /** Number of accepted connections the driver has not closed yet. */
    int openConnections() {
        return open.size();
    }

    /** Waits until the proxy has accepted at least the given number of connections. */
    void awaitAccepted(int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (accepted.get() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the driver did not connect to the server within " + timeout);
            }
            Thread.sleep(20);
        }
    }

    /**
     * Waits until every accepted connection is closed.
     *
     * @return the number of connections still open when the timeout expired
     */
    int awaitAllClosed(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!open.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return open.size();
    }

    @Override
    public void close() {
        closeQuietly(server);
        open.forEach(HarborProxy::closeQuietly);
        open.clear();
        synchronized (this) {
            notifyAll();
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                accepted.incrementAndGet();
                open.add(client);
                start(() -> handle(client), "harbor-proxy-connection");
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        Mode decided = awaitDecision();
        if (decided != Mode.FORWARD) {
            release(client, null);
            return;
        }
        Socket target = new Socket();
        try {
            target.connect(upstream, 5_000);
        } catch (IOException e) {
            release(client, target);
            return;
        }
        start(() -> pump(target, client, client, target), "harbor-proxy-downstream");
        pump(client, target, client, target);
    }

    /** Waits while the proxy holds; returns {@link Mode#DROP} once the proxy is closed. */
    private synchronized Mode awaitDecision() {
        while (mode == Mode.HOLD && !server.isClosed()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Mode.DROP;
            }
        }
        return server.isClosed() ? Mode.DROP : mode;
    }

    /** Copies one direction until either side closes, then closes both. */
    private void pump(Socket from, Socket to, Socket client, Socket target) {
        byte[] buffer = new byte[8192];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int read = in.read(buffer);
            while (read >= 0) {
                out.write(buffer, 0, read);
                out.flush();
                read = in.read(buffer);
            }
        } catch (IOException e) {
            // either side closed: the connection is over
        } finally {
            release(client, target);
        }
    }

    private void release(Socket client, Socket target) {
        closeQuietly(client);
        if (target != null) {
            closeQuietly(target);
        }
        open.remove(client);
    }

    private static void start(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // nothing left to release
        }
    }
}
