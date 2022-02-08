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

package org.eclipse.jetty.maven.plugin;

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.toolchain.test.IO;

/**
 * MockShutdownMonitorRunnable
 * 
 * Mimics the actions of the org.eclipse.jetty.server.ShutdownMonitor.ShutdownMonitorRunnable
 * to aid testing.
 */
public class MockShutdownMonitorRunnable implements Runnable
{
    ServerSocket serverSocket;
    String key;
    String statusResponse = "OK";
    String pidResponse;
    String defaultResponse = "Stopped";
    boolean exit;
    
    public void setExit(boolean exit)
    {
        this.exit = exit;
    }

    public void setKey(String key)
    {
        this.key = key;
    }
    
    public void setServerSocket(ServerSocket serverSocket)
    {
        this.serverSocket = serverSocket;
    }

    public void setPidResponse(String pidResponse)
    {
        this.pidResponse = pidResponse;
    }

    public void run()
    {
        try
        {
            while (true)
            {
                try (Socket socket = serverSocket.accept())
                {
                    LineNumberReader reader = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
                    String receivedKey = reader.readLine();
                    if (!key.equals(receivedKey))
                    {
                        continue;
                    }

                    String cmd = reader.readLine();
                    OutputStream out = socket.getOutputStream();

                    if ("status".equalsIgnoreCase(cmd))
                    {
                        out.write((statusResponse + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    else if ("pid".equalsIgnoreCase(cmd))
                    { 
                        out.write((pidResponse + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    else
                    {
                        out.write((defaultResponse + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        if (exit)
                            System.exit(0);
                    }
                }
                catch (Throwable x)
                {
                    x.printStackTrace();
                }
            }
        }
        catch (Throwable x)
        {
            x.printStackTrace();
        }
        finally
        {
            IO.close(serverSocket);
        }
    }
}
