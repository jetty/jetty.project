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

import java.net.ServerSocket;

import org.eclipse.jetty.toolchain.test.IO;

/**
 * MockShutdownMonitor
 * A helper class that grabs a ServerSocket, spawns a thread and then
 * passes the ServerSocket to the Runnable. This class has a main so
 * that it can be used for forking, to mimic the actions of the
 * org.eclipse.jetty.server.ShutdownMonitor.
 */
public class MockShutdownMonitor
{
    String key;
    MockShutdownMonitorRunnable testerRunnable;
    ServerSocket serverSocket;

    public MockShutdownMonitor(String key, MockShutdownMonitorRunnable testerRunnable)
        throws Exception
    {
        this.key = key;
        this.testerRunnable = testerRunnable;
        listen();
    }

    private ServerSocket listen()
        throws Exception
    {
        serverSocket = new ServerSocket(0);
        try
        {
            serverSocket.setReuseAddress(true);
            return serverSocket;
        }
        catch (Throwable e)
        {
            IO.close(serverSocket);
            throw e;
        }
    }
    
    public int getPort()
    {
        if (serverSocket == null)
            return 0;
        return serverSocket.getLocalPort();
    }

    public void start()
        throws Exception
    {
        testerRunnable.setServerSocket(serverSocket);
        testerRunnable.setKey(key);
        Thread thread = new Thread(testerRunnable);
        thread.setDaemon(true);
        thread.setName("Tester Thread");
        thread.start();
    }
}
