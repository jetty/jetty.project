//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

public class Tester
{
    String key;
    TesterRunnable testerRunnable;
    ServerSocket serverSocket;
    boolean join;

    public Tester(String key, TesterRunnable testerRunnable, boolean join)
        throws Exception
    {
        this.key = key;
        this.testerRunnable = testerRunnable;
        this.join = join;
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
        if (join)
            thread.join();
    }

    public static final void main(String[] args)
    {
        try
        {
            ForkableTesterRunnable testerRunnable = new ForkableTesterRunnable();
            Tester tester = new Tester(args[0], testerRunnable, true);
            System.err.println(tester.getPort());
            tester.start();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}