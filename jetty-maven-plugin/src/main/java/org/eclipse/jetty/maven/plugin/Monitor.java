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


package org.eclipse.jetty.maven.plugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;

import org.eclipse.jetty.server.Server;





/**
 * Monitor
 *
 * Listens for stop commands eg via mvn jetty:stop and
 * causes jetty to stop either by exiting the jvm, or
 * by stopping the Server instances. The choice of
 * behaviour is controlled by either passing true
 * (exit jvm) or false (stop Servers) in the constructor.
 * 
 */
public class Monitor extends Thread
{
    private String _key;
    private Server[] _servers;

    ServerSocket _serverSocket;
    boolean _kill;

    public Monitor(int port, String key, Server[] servers, boolean kill) 
    throws UnknownHostException, IOException
    {
        if (port <= 0)
            throw new IllegalStateException ("Bad stop port");
        if (key==null)
            throw new IllegalStateException("Bad stop key");

        _key = key;
        _servers = servers;
        _kill = kill;
        setDaemon(true);
        setName("StopJettyPluginMonitor");
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);       
        _serverSocket=new ServerSocket();
        _serverSocket.setReuseAddress(true);
        try
        {
            _serverSocket.bind(address,1);
        }
        catch (IOException x)
        {
            System.out.println("Error binding to stop port 127.0.0.1:"+port);
            throw x;
        }
    }
    
    public void run()
    {
        while (_serverSocket != null)
        {
            Socket socket = null;
            try
            {
                socket = _serverSocket.accept();
                socket.setSoLinger(false, 0);
                LineNumberReader lin = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
                
                String key = lin.readLine();
                if (!_key.equals(key)) continue;
                String cmd = lin.readLine();
                if ("stop".equals(cmd))
                {
                    try{socket.close();}catch (Exception e){e.printStackTrace();}
                    try{socket.close();}catch (Exception e){e.printStackTrace();}
                    try{_serverSocket.close();}catch (Exception e){e.printStackTrace();}
                
                    _serverSocket = null;
                    
                    if (_kill)
                    {
                        System.out.println("Killing Jetty");
                        System.exit(0);     
                    }
                    else
                    {
                        for (int i=0; _servers != null && i < _servers.length; i++)
                        {
                            try
                            {
                                System.out.println("Stopping server "+i);                             
                                _servers[i].stop();
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                else
                    System.out.println("Unsupported monitor operation");
            }
            catch (Exception e)
            {
               e.printStackTrace();
            }
            finally
            {
                if (socket != null)
                {
                    try
                    {
                        socket.close();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                socket = null;
            }
        }
    }
}
