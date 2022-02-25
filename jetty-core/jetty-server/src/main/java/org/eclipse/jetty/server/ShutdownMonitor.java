//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ShutdownThread;

/**
 * Shutdown/Stop Monitor thread.
 * <p>
 * This thread listens on the host/port specified by the STOP.HOST/STOP.PORT
 * system parameter (defaults to 127.0.0.1/-1 for not listening) for request
 * authenticated with the key given by the STOP.KEY system parameter
 * for admin requests.
 * <p>
 * If the stop port is set to zero, then a random port is assigned and the
 * port number is printed to stdout.
 * <p>
 * Commands "stop" and "status" are currently supported.
 */
public class ShutdownMonitor
{
    // Implementation of safe lazy init, using Initialization on Demand Holder technique.
    private static class Holder
    {
        static ShutdownMonitor instance = new ShutdownMonitor();
    }

    public static ShutdownMonitor getInstance()
    {
        return Holder.instance;
    }

    protected static void reset()
    {
        Holder.instance = new ShutdownMonitor();
    }

    public static void register(LifeCycle... lifeCycles)
    {
        getInstance().addLifeCycles(lifeCycles);
    }

    public static void deregister(LifeCycle lifeCycle)
    {
        getInstance().removeLifeCycle(lifeCycle);
    }

    public static boolean isRegistered(LifeCycle lifeCycle)
    {
        return getInstance().containsLifeCycle(lifeCycle);
    }

    private final AutoLock.WithCondition _lock = new AutoLock.WithCondition();
    private final Set<LifeCycle> _lifeCycles = new LinkedHashSet<>();
    private boolean debug;
    private final String host;
    private int port;
    private String key;
    private boolean exitVm;
    private boolean alive;

    /**
     * Creates a ShutdownMonitor using configuration from the System properties.
     * <p>
     * <code>STOP.PORT</code> = the port to listen on (empty, null, or values less than 0 disable the stop ability)<br>
     * <code>STOP.KEY</code> = the magic key/passphrase to allow the stop<br>
     * <p>
     * Note: server socket will only listen on localhost, and a successful stop will issue a System.exit() call.
     */
    private ShutdownMonitor()
    {
        this.debug = System.getProperty("DEBUG") != null;
        this.host = System.getProperty("STOP.HOST", "127.0.0.1");
        this.port = Integer.getInteger("STOP.PORT", -1);
        this.key = System.getProperty("STOP.KEY", null);
        this.exitVm = true;
    }

    private void addLifeCycles(LifeCycle... lifeCycles)
    {
        try (AutoLock l = _lock.lock())
        {
            _lifeCycles.addAll(Arrays.asList(lifeCycles));
        }
    }

    private void removeLifeCycle(LifeCycle lifeCycle)
    {
        try (AutoLock l = _lock.lock())
        {
            _lifeCycles.remove(lifeCycle);
        }
    }

    private boolean containsLifeCycle(LifeCycle lifeCycle)
    {
        try (AutoLock l = _lock.lock())
        {
            return _lifeCycles.contains(lifeCycle);
        }
    }

    private void debug(String format, Object... args)
    {
        if (debug)
            System.err.printf("[ShutdownMonitor] " + format + "%n", args);
    }

    private void debug(Throwable t)
    {
        if (debug)
            t.printStackTrace(System.err);
    }

    public String getKey()
    {
        try (AutoLock l = _lock.lock())
        {
            return key;
        }
    }

    public int getPort()
    {
        try (AutoLock l = _lock.lock())
        {
            return port;
        }
    }

    public boolean isExitVm()
    {
        try (AutoLock l = _lock.lock())
        {
            return exitVm;
        }
    }

    public void setDebug(boolean flag)
    {
        this.debug = flag;
    }

    /**
     * @param exitVm true to exit the VM on shutdown
     */
    public void setExitVm(boolean exitVm)
    {
        try (AutoLock l = _lock.lock())
        {
            if (alive)
                throw new IllegalStateException("ShutdownMonitor already started");
            this.exitVm = exitVm;
        }
    }

    public void setKey(String key)
    {
        try (AutoLock l = _lock.lock())
        {
            if (alive)
                throw new IllegalStateException("ShutdownMonitor already started");
            this.key = key;
        }
    }

    public void setPort(int port)
    {
        try (AutoLock l = _lock.lock())
        {
            if (alive)
                throw new IllegalStateException("ShutdownMonitor already started");
            this.port = port;
        }
    }

    protected void start() throws Exception
    {
        try (AutoLock l = _lock.lock())
        {
            if (alive)
            {
                debug("Already started");
                return; // cannot start it again
            }
            ServerSocket serverSocket = listen();
            if (serverSocket != null)
            {
                alive = true;
                Thread thread = new Thread(new ShutdownMonitorRunnable(serverSocket));
                thread.setDaemon(true);
                thread.setName("ShutdownMonitor");
                thread.start();
            }
        }
    }

    private void stop()
    {
        try (AutoLock.WithCondition l = _lock.lock())
        {
            alive = false;
            l.signalAll();
        }
    }

    // For test purposes only.
    void await() throws InterruptedException
    {
        try (AutoLock.WithCondition l = _lock.lock())
        {
            while (alive)
            {
                l.await();
            }
        }
    }

    protected boolean isAlive()
    {
        try (AutoLock l = _lock.lock())
        {
            return alive;
        }
    }

    private ServerSocket listen()
    {
        int port = getPort();
        if (port < 0)
        {
            debug("Not enabled (port < 0): %d", port);
            return null;
        }

        String key = getKey();
        try
        {
            ServerSocket serverSocket = new ServerSocket();
            try
            {
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(InetAddress.getByName(host), port));
            }
            catch (Throwable e)
            {
                IO.close(serverSocket);
                throw e;
            }
            if (port == 0)
            {
                port = serverSocket.getLocalPort();
                System.out.printf("STOP.PORT=%d%n", port);
                setPort(port);
            }

            if (key == null)
            {
                key = Long.toString((long)(Long.MAX_VALUE * Math.random() + this.hashCode() + System.currentTimeMillis()), 36);
                System.out.printf("STOP.KEY=%s%n", key);
                setKey(key);
            }

            return serverSocket;
        }
        catch (Throwable x)
        {
            debug(x);
            System.err.println("Error binding ShutdownMonitor to port " + port + ": " + x.toString());
            return null;
        }
        finally
        {
            // establish the port and key that are in use
            debug("STOP.PORT=%d", port);
            debug("STOP.KEY=%s", key);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[port=%d,alive=%b]", this.getClass().getName(), getPort(), isAlive());
    }

    /**
     * Thread for listening to STOP.PORT for command to stop Jetty.
     * If ShutdownMonitor.exitVm is true, then System.exit will also be
     * called after the stop.
     */
    private class ShutdownMonitorRunnable implements Runnable
    {
        private final ServerSocket serverSocket;

        private ShutdownMonitorRunnable(ServerSocket serverSocket)
        {
            this.serverSocket = serverSocket;
        }

        @Override
        public void run()
        {
            debug("Started");
            try
            {
                String key = getKey();
                while (true)
                {
                    try (Socket socket = serverSocket.accept())
                    {
                        LineNumberReader reader = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
                        String receivedKey = reader.readLine();
                        if (!key.equals(receivedKey))
                        {
                            debug("Ignoring command with incorrect key: %s", receivedKey);
                            continue;
                        }

                        String cmd = reader.readLine();
                        debug("command=%s", cmd);
                        OutputStream out = socket.getOutputStream();
                        boolean exitVm = isExitVm();

                        if ("stop".equalsIgnoreCase(cmd)) //historic, for backward compatibility
                        {
                            //Stop the lifecycles, only if they are registered with the ShutdownThread, only destroying if vm is exiting
                            debug("Performing stop command");
                            stopLifeCycles(ShutdownThread::isRegistered, exitVm);

                            // Reply to client
                            debug("Informing client that we are stopped");
                            informClient(out, "Stopped\r\n");

                            if (!exitVm)
                                break;

                            // Kill JVM
                            debug("Killing JVM");
                            System.exit(0);
                        }
                        else if ("forcestop".equalsIgnoreCase(cmd))
                        {
                            debug("Performing forced stop command");
                            stopLifeCycles(l -> true, exitVm);

                            // Reply to client
                            debug("Informing client that we are stopped");
                            informClient(out, "Stopped\r\n");

                            if (!exitVm)
                                break;

                            // Kill JVM
                            debug("Killing JVM");
                            System.exit(0);
                        }
                        else if ("stopexit".equalsIgnoreCase(cmd))
                        {
                            debug("Performing stop and exit commands");
                            stopLifeCycles(ShutdownThread::isRegistered, true);

                            // Reply to client
                            debug("Informing client that we are stopped");
                            informClient(out, "Stopped\r\n");

                            debug("Killing JVM");
                            System.exit(0);
                        }
                        else if ("exit".equalsIgnoreCase(cmd))
                        {
                            debug("Killing JVM");
                            System.exit(0);
                        }
                        else if ("status".equalsIgnoreCase(cmd))
                        {
                            // Reply to client
                            informClient(out, "OK\r\n");
                        }
                    }
                    catch (Throwable x)
                    {
                        debug(x);
                    }
                }
            }
            catch (Throwable x)
            {
                debug(x);
            }
            finally
            {
                IO.close(serverSocket);
                stop();
                debug("Stopped");
            }
        }

        private void informClient(OutputStream out, String message) throws IOException
        {
            out.write(message.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void stopLifeCycles(Predicate<LifeCycle> predicate, boolean destroy)
        {
            List<LifeCycle> lifeCycles;
            try (AutoLock l = _lock.lock())
            {
                lifeCycles = new ArrayList<>(_lifeCycles);
            }

            for (LifeCycle l : lifeCycles)
            {
                try
                {
                    if (l.isStarted() && predicate.test(l))
                        l.stop();

                    if ((l instanceof Destroyable) && destroy)
                        ((Destroyable)l).destroy();
                }
                catch (Throwable x)
                {
                    debug(x);
                }
            }
        }
    }
}
