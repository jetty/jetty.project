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

package org.eclipse.jetty.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;

/* ------------------------------------------------------------ */
/**
 * Monitor
 *
 * Listens for stop commands eg via mvn jetty:stop and
 * causes jetty to stop by exiting the virtual machine
 * 
 */
public class Monitor extends Thread
{
    private String _key;

    private ServerSocket _serverSocket;

    /* ------------------------------------------------------------ */
    public Monitor(int port, String key) 
        throws UnknownHostException, IOException
    {
        if (port <= 0)
            throw new IllegalStateException ("Bad stop port");
        if (key==null)
            throw new IllegalStateException("Bad stop key");

        _key = key;

        setDaemon(true);
        setName("JettyRunnerMonitor");
        
        _serverSocket=new ServerSocket(port,1,InetAddress.getByName("127.0.0.1")); 
        _serverSocket.setReuseAddress(true);
    }
    
    /* ------------------------------------------------------------ */
    public void run()
    {
        while (_serverSocket != null)
        {
            Socket socket = null;
            try
            {
                socket = _serverSocket.accept();
                socket.setSoLinger(false, 0);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                String key = reader.readLine();
                if (!_key.equals(key)) continue;
                String cmd = reader.readLine();
                if ("stop".equals(cmd))
                {
                    closeSocket(socket);
                    closeServerSocket(_serverSocket);
                    
                    System.err.println("Stopping Jetty");
                    System.exit(0);     
                }
                else
                    System.err.println("Unsupported monitor operation: "+cmd);
            }
            catch (Exception ex)
            {
                ex.printStackTrace();
            }
            finally
            {
                closeSocket(socket);
            }
        }
    }

    /* ------------------------------------------------------------ */
    private void closeSocket(Socket socket)
    {
        if (socket != null)
        {
            try
            {
                socket.close();
            }
            catch (Exception ex)
            {
                ex.printStackTrace();
            }
            socket = null;
        }
    }

    /* ------------------------------------------------------------ */
    private void closeServerSocket(ServerSocket socket)
    {
        if (socket != null)
        {
            try
            {
                socket.close();
            }
            catch (Exception ex)
            {
                ex.printStackTrace();
            }
            socket = null;
        }
    }
}
