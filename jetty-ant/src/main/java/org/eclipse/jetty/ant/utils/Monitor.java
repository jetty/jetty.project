//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.ant.utils;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;

import org.eclipse.jetty.server.Server;


/**
 * Monitor
 *
 *
 */
public class Monitor extends Thread
{
    private String stopKey;

    private Server[] servers;

    private ServerSocket serverSocket;
    
    public Monitor(int port, String key, Server[] servers)
    throws Exception
    {
        if (port <= 0) throw new IllegalStateException("Bad stop port");
        if (key == null) throw new IllegalStateException("Bad stop key");
        if (servers == null) throw new IllegalStateException("No servers");

        this.stopKey = key;
        this.servers = servers;
        setName("JettyStopTaskMonitor");
        setDaemon(true);
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        try
        {
            serverSocket.bind(address, 1);
            TaskLog.log("Jetty monitoring port 127.0.0.1:" + port);
        }
        catch (IOException x)
        {
            TaskLog.log("Error binding to stop port 127.0.0.1:" + port);
            throw x;
        }
    }

    /**
     * @see java.lang.Thread#run()
     */
    public void run()
    {
        while (serverSocket != null)
        {
            Socket socket = null;
            try
            {
                socket = serverSocket.accept();
                socket.setSoLinger(false, 0);
                LineNumberReader lin = new LineNumberReader(new InputStreamReader(socket.getInputStream()));

                String key = lin.readLine();
                System.err.println("Monitor: " + key);
                if (!stopKey.equals(key)) continue;
                String cmd = lin.readLine();
                if ("stop".equals(cmd))
                {
                    close(serverSocket);
                    serverSocket = null;
                    for (Server s : servers)
                    {
                        try
                        {
                            TaskLog.log("Stopping server: " + s);
                            s.stop();
                            TaskLog.log("Stopped server: " + s);
                        }
                        catch (Exception e)
                        {
                            TaskLog.log(e.getMessage());
                        }
                    }

                    // confirm the stop
                    socket.getOutputStream().write("Stopped\r\n".getBytes());
                }
                else
                    TaskLog.log("Unsupported monitor operation: " + cmd);
            }
            catch (Exception e)
            {
                TaskLog.log(e.getMessage());
            }
            finally
            {
                close(socket);
                socket = null;
                close(serverSocket);
            }
        }
    }

    /**
     * @param c
     */
    private void close(Closeable c)
    {
        if (c == null) return;

        try
        {
            c.close();
        }
        catch (Exception e)
        {
            TaskLog.log(e.getMessage());
        }
    }
}
