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

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.toolchain.test.IO;

public class ForkableTesterRunnable extends TesterRunnable
{
    @Override
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

                    if ("stop".equalsIgnoreCase(cmd)) 
                    {
                        out.write((stopResponse + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        System.exit(0);
                    }
                    else if ("forcestop".equalsIgnoreCase(cmd))
                    {
                        out.write((forceStopResponse + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        System.exit(0);
                    }
                    else if ("stopexit".equalsIgnoreCase(cmd))
                    {
                        out.write((stopExitResponse + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        System.exit(0);
                    }
                    else if ("exit".equalsIgnoreCase(cmd))
                    {
                        out.write((exitResponse + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        System.exit(0);
                    }
                    else if ("status".equalsIgnoreCase(cmd))
                    {
                        out.write(("OK\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                    else if ("pid".equalsIgnoreCase(cmd))
                    { 
                        out.write((String.valueOf(ProcessHandle.current().pid()) + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
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