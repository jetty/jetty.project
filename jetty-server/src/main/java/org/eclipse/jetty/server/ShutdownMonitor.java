//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.ShutdownThread;

/**
 * Shutdown/Stop Monitor thread.
 * <p>
 * This thread listens on the port specified by the STOP.PORT system parameter (defaults to -1 for not listening) for request authenticated with the key given
 * by the STOP.KEY system parameter (defaults to "eclipse") for admin requests.
 * <p>
 * If the stop port is set to zero, then a random port is assigned and the port number is printed to stdout.
 * <p>
 * Commands "stop" and "status" are currently supported.
 */
public class ShutdownMonitor extends Thread
{
    // Implementation of safe lazy init, using Initialization on Demand Holder technique.
    static class Holder
    {
        static ShutdownMonitor instance = new ShutdownMonitor();
    }

    public static ShutdownMonitor getInstance()
    {
        return Holder.instance;
    }

    private boolean DEBUG;
    private int port;
    private String key;
    private boolean exitVm;
    private ServerSocket serverSocket;

    /**
     * Create a ShutdownMonitor using configuration from the System properties.
     * <p>
     * <code>STOP.PORT</code> = the port to listen on (empty, null, or values less than 0 disable the stop ability)<br>
     * <code>STOP.KEY</code> = the magic key/passphrase to allow the stop (defaults to "eclipse")<br>
     * <p>
     * Note: server socket will only listen on localhost, and a successful stop will issue a System.exit() call.
     */
    private ShutdownMonitor()
    {
        Properties props = System.getProperties();

        this.DEBUG = props.containsKey("DEBUG");

        // Use values passed thru via /jetty-start/
        this.port = Integer.parseInt(props.getProperty("STOP.PORT","-1"));
        this.key = props.getProperty("STOP.KEY","eclipse");
        this.exitVm = true;
    }

    private void close(ServerSocket server)
    {
        if (server == null)
        {
            return;
        }

        try
        {
            server.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }

    private void close(Socket socket)
    {
        if (socket == null)
        {
            return;
        }

        try
        {
            socket.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }

    private void debug(String format, Object... args)
    {
        if (DEBUG)
        {
            System.err.printf("[ShutdownMonitor] " + format + "%n",args);
        }
    }

    private void debug(Throwable t)
    {
        if (DEBUG)
        {
            t.printStackTrace(System.err);
        }
    }

    public String getKey()
    {
        return key;
    }

    public int getPort()
    {
        return port;
    }

    public ServerSocket getServerSocket()
    {
        return serverSocket;
    }

    public boolean isExitVm()
    {
        return exitVm;
    }

    @Override
    public void run()
    {
        if (serverSocket == null)
        {
            return;
        }

        while (serverSocket != null)
        {
            Socket socket = null;
            try
            {
                socket = serverSocket.accept();

                LineNumberReader lin = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
                String key = lin.readLine();
                if (!this.key.equals(key))
                {
                    System.err.println("Ignoring command with incorrect key");
                    continue;
                }

                OutputStream out = socket.getOutputStream();

                String cmd = lin.readLine();
                debug("command=%s",cmd);
                if ("stop".equals(cmd))
                {
                    // Graceful Shutdown
                    debug("Issuing graceful shutdown..");
                    ShutdownThread.getInstance().run();

                    // Reply to client
                    debug("Informing client that we are stopped.");
                    out.write("Stopped\r\n".getBytes(StringUtil.__UTF8));
                    out.flush();

                    // Shutdown Monitor
                    debug("Shutting down monitor");
                    close(socket);
                    socket = null;
                    close(serverSocket);
                    serverSocket = null;

                    if (exitVm)
                    {
                        // Kill JVM
                        debug("Killing JVM");
                        System.exit(0);
                    }
                }
                else if ("status".equals(cmd))
                {
                    // Reply to client
                    out.write("OK\r\n".getBytes(StringUtil.__UTF8));
                    out.flush();
                }
            }
            catch (Exception e)
            {
                debug(e);
                System.err.println(e.toString());
            }
            finally
            {
                close(socket);
                socket = null;
            }
        }
    }

    public void setDebug(boolean flag)
    {
        this.DEBUG = flag;
    }

    public void setExitVm(boolean exitVm)
    {
        if (isAlive())
        {
            throw new IllegalStateException("ShutdownMonitor already started");
        }
        this.exitVm = exitVm;
    }

    public void setKey(String key)
    {
        if (isAlive())
        {
            throw new IllegalStateException("ShutdownMonitor already started");
        }
        this.key = key;
    }

    public void setPort(int port)
    {
        if (isAlive())
        {
            throw new IllegalStateException("ShutdownMonitor already started");
        }
        this.port = port;
    }

    public void start()
    {
        if (isAlive())
        {
            System.err.printf("ShutdownMonitor already started");
            return; // cannot start it again
        }
        startListenSocket();
        if (serverSocket == null)
        {
            return;
        }

        super.start();
    }

    private void startListenSocket()
    {
        if (this.port < 0)
        {            
            if (DEBUG)
                System.err.println("ShutdownMonitor not in use (port < 0): " + port);
            return;
        }

        try
        {
            setDaemon(true);
            setName("ShutdownMonitor");

            this.serverSocket = new ServerSocket(this.port,1,InetAddress.getByName("127.0.0.1"));
            if (this.port == 0)
            {
                // server assigned port in use
                this.port = serverSocket.getLocalPort();
                System.out.printf("STOP.PORT=%d%n",this.port);
            }

            if (this.key == null)
            {
                // create random key
                this.key = Long.toString((long)(Long.MAX_VALUE * Math.random() + this.hashCode() + System.currentTimeMillis()),36);
                System.out.printf("STOP.KEY=%s%n",this.key);
            }
        }
        catch (Exception e)
        {
            debug(e);
            System.err.println("Error binding monitor port " + this.port + ": " + e.toString());
        }
        finally
        {
            // establish the port and key that are in use
            debug("STOP.PORT=%d",this.port);
            debug("STOP.KEY=%s",this.key);
            debug("%s",serverSocket);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[port=%d]",this.getClass().getName(),port);
    }
}
