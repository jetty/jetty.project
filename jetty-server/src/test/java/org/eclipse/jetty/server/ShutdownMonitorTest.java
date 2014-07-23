//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * ShutdownMonitorTest
 */
public class ShutdownMonitorTest
{
    @Test
    public void testShutdown() throws Exception
    {
        // test port and key assignment
        ShutdownMonitor.getInstance().setPort(0);
        ShutdownMonitor.getInstance().setExitVm(false);
        ShutdownMonitor.getInstance().start();
        String key = ShutdownMonitor.getInstance().getKey();
        int port = ShutdownMonitor.getInstance().getPort();

        // try starting a 2nd time (should be ignored)
        ShutdownMonitor.getInstance().start();

        stop(port,key,true);
        assertTrue(!ShutdownMonitor.getInstance().isAlive());

        // should be able to change port and key because it is stopped
        ShutdownMonitor.getInstance().setPort(0);
        ShutdownMonitor.getInstance().setKey("foo");
        ShutdownMonitor.getInstance().start();

        key = ShutdownMonitor.getInstance().getKey();
        port = ShutdownMonitor.getInstance().getPort();
        assertTrue(ShutdownMonitor.getInstance().isAlive());

        stop(port,key,true);
        assertTrue(!ShutdownMonitor.getInstance().isAlive());
    }

    public void stop(int port, String key, boolean check) throws Exception
    {
        System.out.printf("Attempting stop to localhost:%d (%b)%n",port,check);
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"),port))
        {
            // send stop command
            try (OutputStream out = s.getOutputStream())
            {
                out.write((key + "\r\nstop\r\n").getBytes());
                out.flush();

                if (check)
                {
                    // wait a little
                    TimeUnit.MILLISECONDS.sleep(600);

                    // check for stop confirmation
                    LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));
                    String response;
                    if ((response = lin.readLine()) != null)
                    {
                        assertEquals("Stopped",response);
                    }
                    else
                        throw new IllegalStateException("No stop confirmation");
                }
            }
        }
    }

}
