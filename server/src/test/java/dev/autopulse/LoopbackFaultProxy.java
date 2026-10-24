package dev.autopulse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only TCP cut: interrupts real streams, unlike Chromium's offline flag. */
final class LoopbackFaultProxy implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final WatchService watcher;
    private volatile boolean offline;
    private volatile boolean closed;

    LoopbackFaultProxy(int targetPort, Path control) throws IOException {
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        watcher = control.getFileSystem().newWatchService();
        control.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        Thread.startVirtualThread(() -> {
            while (!closed) {
                try {
                    var key = watcher.take();
                    for (var event : key.pollEvents()) {
                        if (!event.context().equals(control.getFileName()) || !Files.exists(control)) continue;
                        String command = Files.readString(control);
                        offline = command.equals("offline");
                        if (offline) sockets.forEach(LoopbackFaultProxy::closeSocket);
                        Files.writeString(control.resolveSibling(control.getFileName() + ".ack"), command);
                    }
                    key.reset();
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
                catch (IOException | java.nio.file.ClosedWatchServiceException error) { return; }
            }
        });
        Thread.startVirtualThread(() -> {
            while (!closed) {
                try {
                    Socket inbound = listener.accept();
                    if (offline || sockets.size() >= 64) { closeSocket(inbound); continue; }
                    Socket outbound = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                    sockets.add(inbound); sockets.add(outbound);
                    Thread.startVirtualThread(() -> copy(inbound, outbound));
                    Thread.startVirtualThread(() -> copy(outbound, inbound));
                } catch (IOException error) { if (closed) return; }
            }
        });
    }
    private void copy(Socket from, Socket to) {
        try { from.getInputStream().transferTo(to.getOutputStream()); }
        catch (IOException disconnected) { /* expected during the controlled fault */ }
        finally { closeSocket(from); closeSocket(to); sockets.remove(from); sockets.remove(to); }
    }
    int port() { return listener.getLocalPort(); }
    private static void closeSocket(Socket socket) { try { socket.close(); } catch (IOException ignored) {} }
    public void close() throws IOException {
        closed = true; listener.close(); watcher.close(); sockets.forEach(LoopbackFaultProxy::closeSocket);
    }
}
