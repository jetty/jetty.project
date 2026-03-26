//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Server} Shutdown Service, which will listen
 * to a configured Host / Port and handle commands to control the
 * list of Jetty Component {@link LifeCycle} objects.
 *
 * <p>
 *   The commands you can send to this Server are always in {@code US_ASCII}
 *   with a CRLF ({@code \r\n}.
 * </p>
 *
 * <p>
 *   Supported commands:
 * </p>
 *
 * <dl>
 * <dt>{@code FORCESTOP}</dt>
 * <dd>Will stop components by calling {@link LifeCycle#stop()}.<br>
 *     Will receive {@code Stopped\r\n} after stop, and before exit.
 * </dd>
 *
 * <dt>{@code STOPEXIT}</dt>
 * <dd>Will stop components by calling {@link LifeCycle#stop()},<br>
 *     (Each component that is a {@link Destroyable} will also be {@link Destroyable#destroy() destroyed})
 *     followed by {@code System.exit(0);} at the end.<br>
 *     Will receive {@code Stopped\r\n} after stop/destroy, and before exit.</dd>
 *
 * <dt>{@code EXIT}</dt>
 * <dd>Will simply call {@code System.exit(0);} on JVM</dd>
 *
 * <dt>{@code STATUS}</dt>
 * <dd>Will return {@code OK\r\n} to indicate ShutdownService is alive and ready to take commands.</dd>
 *
 * <dt>{@code PID}</dt>
 * <dd>Will return PID of the running JVM from {@link ProcessHandle#pid() ProcessHandle.current().pid()} on JVM</dd>
 * </dl>
 *
 * @since 12.1.0
 */
public class ShutdownService
{
    private static final Logger LOG = LoggerFactory.getLogger(ShutdownService.class);
    protected final AutoLock lock = new AutoLock();
    // Host to listen on
    private final String host;
    // Port to listen on
    private final int port;
    private final String key;
    private final boolean exitVm;
    protected final List<LifeCycle> components = new ArrayList<>();
    private ServerSocket serverSocket;

    /**
     * Create a new ShutdownService.
     *
     * @param host the host
     * @param port the port
     * @param key the key that must be passed to allow a shutdown
     * @param exitVm flag to exit vm on successful shutdown
     */
    public ShutdownService(@Name("host") String host,
                           @Name("port") int port,
                           @Name("key") String key,
                           @Name("exitVm") boolean exitVm)
    {
        if (StringUtil.isBlank(host))
            throw new IllegalArgumentException("Host is unset");
        this.host = host;
        if (port < 0 || port > 0xFFFF)
            throw new IllegalArgumentException("Invalid Port: " + port);
        this.port = port;
        this.key = StringUtil.isNotBlank(key) ? key : generateKey();
        this.exitVm = exitVm;
    }

    private String generateKey()
    {
        return Long.toString((long)(Long.MAX_VALUE * Math.random() + this.hashCode() + System.currentTimeMillis()), 36);
    }

    public void addComponent(LifeCycle component)
    {
        try (AutoLock l = lock.lock())
        {
            // TODO: should we reject calls to this method after the ServerSocket is started?
            this.components.add(component);
        }
    }

    public boolean removeComponent(LifeCycle component)
    {
        // TODO: should we reject calls to this method after the ServerSocket is started?
        boolean ret = false;
        try (AutoLock l = lock.lock())
        {
            ret = this.components.remove(component);
        }

        return ret;
    }

    public boolean hasComponent(LifeCycle component)
    {
        boolean ret;
        try (AutoLock l = lock.lock())
        {
            ret = this.components.contains(component);
        }

        return ret;
    }

    public void start() throws Exception
    {
        try (AutoLock l = lock.lock())
        {
            if (serverSocket != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Already started: {}", this);
                return; // cannot start it again
            }

            listen();
            if (serverSocket != null)
            {
                Thread thread = new Thread(new ShutdownService.ShutdownRunnable(serverSocket));
                thread.setDaemon(true);
                thread.setName(TypeUtil.toShortName(this.getClass()));
                thread.start();
            }
        }
    }

    public void stop()
    {
        IO.close(serverSocket);
        if (LOG.isDebugEnabled())
            LOG.debug("Closed ServerSocket");
        serverSocket = null;
    }

    public boolean isExitVm()
    {
        return exitVm;
    }

    public String getHost()
    {
        return host;
    }

    public String getKey()
    {
        return key;
    }

    /**
     * Get the configured port.
     * <p>
     *     If configured for port 0, then the automatically allocated port is available on {@link #getLocalPort()}
     * </p>
     *
     * @return the configured port
     */
    public int getPort()
    {
        return port;
    }

    /**
     * Get the {@link ServerSocket#getLocalPort()} that this is listening on.
     * @return the port that the server socket is listening on.
     */
    public int getLocalPort()
    {
        if (serverSocket != null)
        {
            return serverSocket.getLocalPort();
        }
        return -1;
    }

    public boolean isListening()
    {
        try (AutoLock l = lock.lock())
        {
            if (serverSocket == null)
                return false;

            System.out.printf("%s [isBound=%b, isClosed=%b]%n",
                this, serverSocket.isBound(), serverSocket.isClosed());
            return serverSocket.isBound() && !serverSocket.isClosed();
        }
    }

    /**
     * Configure the ServerSocket before binding.
     *
     * @param serverSocket the server socket.
     */
    protected void configure(ServerSocket serverSocket) throws SocketException
    {
        serverSocket.setReuseAddress(true);
    }

    /**
     * Event triggered with the ServerSocket is bound.
     *
     * @param serverSocket the bound server socket.
     */
    protected void bound(ServerSocket serverSocket)
    {
    }

    private void listen()
    {
        try
        {
            serverSocket = new ServerSocket();
            configure(serverSocket);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(getHost()), getPort()));
            // Output to STDOUT the stop details (used by scripting to know the port and key)
            System.out.printf("STOP.PORT=%d%n", getLocalPort());
            System.out.printf("STOP.KEY=%s%n", getKey());
            System.out.printf("STOP.EXIT=%s%n", isExitVm());
        }
        catch (Throwable x)
        {
            IO.close(serverSocket);
            LOG.warn("Failed to start ServerSocket on {}:{}", getHost(), getPort(), x);
            serverSocket = null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%H{host=%s,port=%s,key=%s,exitVm=%b,serverSocket=%s}",
            TypeUtil.toShortName(this.getClass()),
            hashCode(),
            getHost(),
            getLocalPort(),
            getKey(),
            isExitVm(),
            serverSocket
        );
    }

    /**
     * Thread handling incoming ServerSocket accept events.
     */
    private class ShutdownRunnable implements Runnable
    {
        private final ServerSocket serverSocket;

        private ShutdownRunnable(ServerSocket serverSocket)
        {
            this.serverSocket = serverSocket;
        }

        @Override
        public void run()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Started: {}", ShutdownService.this);
            try
            {
                boolean processCommands = true;
                String key = getKey();
                while (processCommands)
                {
                    try (Socket socket = serverSocket.accept())
                    {
                        LineNumberReader reader = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
                        String receivedKey = reader.readLine();
                        if (!key.equals(receivedKey))
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Ignoring command with incorrect key: {}", receivedKey);
                            continue;
                        }

                        String cmd = reader.readLine();
                        if (LOG.isDebugEnabled())
                            LOG.debug("command={}", cmd);
                        OutputStream out = socket.getOutputStream();
                        boolean exitVm = isExitVm();

                        String cmdLower = cmd.toLowerCase(Locale.ENGLISH);

                        switch (cmdLower)
                        {
                            case "stop" -> // historic, for backward compatibility
                            {
                                // Stop the lifecycles, only if they are registered with the ShutdownThread, only destroying if vm is exiting
                                LOG.info("Performing 'stop' command");
                                stopComponents(exitVm);

                                // Reply to client
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Informing client that we are stopped");
                                flush(out, "Stopped\r\n");

                                processCommands = false;
                                if (exitVm)
                                {
                                    // Kill JVM
                                    LOG.info("Exiting JVM");
                                    System.exit(0);
                                }
                            }
                            case "forcestop" ->
                            {
                                LOG.info("Performing `forced stop` command");
                                stopComponents(exitVm);

                                // Reply to client
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Informing client that we are stopped");
                                flush(out, "Stopped\r\n");

                                processCommands = false;
                                if (exitVm)
                                {
                                    // Kill JVM
                                    LOG.info("Exiting JVM");
                                    System.exit(0);
                                }
                            }
                            case "stopexit" ->
                            {
                                LOG.info("Performing `stop` and `exit` commands");
                                stopComponents(true);

                                // Reply to client
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Informing client that we are stopped");
                                flush(out, "Stopped\r\n");

                                processCommands = false;

                                LOG.info("Killing JVM");
                                System.exit(0);
                            }
                            case "exit" ->
                            {
                                processCommands = false;

                                LOG.info("Killing JVM");
                                System.exit(0);
                            }
                            case "status" ->
                            {
                                // Reply to client
                                flush(out, "OK\r\n");
                            }
                            case "pid" ->
                            {
                                flush(out, Long.toString(ProcessHandle.current().pid()));
                            }
                        }
                    }
                    catch (Throwable x)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Failed to handle incoming shutdown client", x);
                    }
                }
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failed ServerSocket", x);
            }
            finally
            {
                stop();
            }
        }

        private void flush(OutputStream out, String message) throws IOException
        {
            out.write(message.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void stopComponents(boolean destroy)
        {
            List<LifeCycle> components;
            try (AutoLock l = lock.lock())
            {
                components = new ArrayList<>(ShutdownService.this.components);
            }

            for (LifeCycle component : components)
            {
                try
                {
                    if (component.isStarted())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Calling stop() on {}", component);
                        component.stop();
                    }

                    if ((component instanceof Destroyable) && destroy)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Calling destroy() on {}", component);
                        ((Destroyable)component).destroy();
                    }
                }
                catch (Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unable to stop component: {}", component, x);
                }
            }
        }
    }
}
