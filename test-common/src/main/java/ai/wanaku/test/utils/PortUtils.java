package ai.wanaku.test.utils;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.wanaku.test.WanakuTestConstants;

/**
 * Utility class for dynamic port allocation.
 * Uses ServerSocket(0) pattern with retry logic for race condition handling.
 */
public final class PortUtils {

    private static final Logger LOG = LoggerFactory.getLogger(PortUtils.class);

    private PortUtils() {
        // Utility class
    }

    /**
     * Finds an available port using ServerSocket(0).
     *
     * @return an available port number
     * @throws IllegalStateException if no port could be allocated
     */
    public static int findAvailablePort() {
        return findAvailablePortWithRetry(WanakuTestConstants.PORT_ALLOCATION_RETRIES);
    }

    /**
     * Finds distinct available ports. All sockets stay open until every port is allocated, so the
     * operating system cannot return the same port twice.
     *
     * @param count number of ports to allocate
     * @return distinct available port numbers
     * @throws IllegalStateException if the ports could not be allocated
     */
    public static int[] findAvailablePorts(int count) {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket(0);
                sockets.add(socket);
                ports[i] = socket.getLocalPort();
            }
            return ports;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate " + count + " ports", e);
        } finally {
            for (ServerSocket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException e) {
                    LOG.warn("Failed to release port {}: {}", socket.getLocalPort(), e.getMessage());
                }
            }
        }
    }

    /**
     * Finds an available port with retry logic.
     *
     * @param maxRetries maximum number of retry attempts
     * @return an available port number
     * @throws IllegalStateException if no port could be allocated after retries
     */
    public static int findAvailablePortWithRetry(int maxRetries) {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                int port = allocatePort();
                if (isPortAvailable(port)) {
                    LOG.debug("Allocated port {} on attempt {}", port, attempt + 1);
                    return port;
                }
            } catch (IOException e) {
                LOG.warn("Port allocation attempt {} failed: {}", attempt + 1, e.getMessage());
            }
            // Small delay between retries to reduce race conditions
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Port allocation interrupted", ie);
            }
        }
        throw new IllegalStateException("Failed to allocate port after " + maxRetries + " retries");
    }

    /**
     * Allocates a port using ServerSocket(0).
     *
     * @return the allocated port number
     * @throws IOException if port allocation fails
     */
    private static int allocatePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /**
     * Checks if a port is currently available.
     *
     * @param port the port to check
     * @return true if the port is available, false otherwise
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
