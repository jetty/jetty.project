//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.thread.ShutdownThread;
import org.junit.Test;

/**
 * ShutdownMonitorTest
 */
public class ShutdownMonitorTest
{
    public class TestableServer extends Server
    {
        boolean destroyed = false;
        boolean stopped = false;
        @Override
        protected void doStop() throws Exception
        {
            stopped = true;
            super.doStop();
        }
        @Override
        public void destroy()
        {
            destroyed = true;
            super.destroy();
        }
        @Override
        protected void doStart() throws Exception
        {
            stopped = false;
            destroyed  = false;
            super.doStart();
        }    
    }
    
    
    @Test
    public void testShutdownMonitor() throws Exception
    {
        // test port and key assignment
        ShutdownMonitor.getInstance().setPort(0);
        ShutdownMonitor.getInstance().setExitVm(false);
        ShutdownMonitor.getInstance().start();
        String key = ShutdownMonitor.getInstance().getKey();
        int port = ShutdownMonitor.getInstance().getPort();

        // try starting a 2nd time (should be ignored)
        ShutdownMonitor.getInstance().start();

        stop("stop", port,key,true);
        assertTrue(!ShutdownMonitor.getInstance().isAlive());

        // should be able to change port and key because it is stopped
        ShutdownMonitor.getInstance().setPort(0);
        ShutdownMonitor.getInstance().setKey("foo");
        ShutdownMonitor.getInstance().start();

        key = ShutdownMonitor.getInstance().getKey();
        port = ShutdownMonitor.getInstance().getPort();
        assertTrue(ShutdownMonitor.getInstance().isAlive());

        stop("stop", port,key,true);
        assertTrue(!ShutdownMonitor.getInstance().isAlive());
    }
    
    
    @Test
    public void testForceStopCommand() throws Exception
    {
        //create a testable Server with stop(), destroy() overridden to instrument
        //start server
        //call "forcestop" and check that server stopped but not destroyed
        // test port and key assignment
        System.setProperty("DEBUG", "true");
        ShutdownMonitor.getInstance().setPort(0);
        TestableServer server = new TestableServer();
        server.start();
       
        //shouldn't be registered for shutdown on jvm
        assertTrue(!ShutdownThread.isRegistered(server));
        assertTrue(ShutdownMonitor.isRegistered(server));
        
        String key = ShutdownMonitor.getInstance().getKey();
        int port = ShutdownMonitor.getInstance().getPort();
        
        stop("forcestop", port,key,true);
        
        assertTrue(!ShutdownMonitor.getInstance().isAlive());
        assertTrue(server.stopped);
        assertTrue(!server.destroyed);
        assertTrue(!ShutdownThread.isRegistered(server));
        assertTrue(!ShutdownMonitor.isRegistered(server));
    }
    
    @Test
    public void testOldStopCommandWithStopOnShutdownTrue() throws Exception
    {
        
        //create a testable Server with stop(), destroy() overridden to instrument
        //call server.setStopAtShudown(true);
        //start server
        //call "stop" and check that server stopped but not destroyed
        
        //stop server
        
        //call server.setStopAtShutdown(false);
        //start server
        //call "stop" and check that the server is not stopped and not destroyed
        System.setProperty("DEBUG", "true");
        ShutdownMonitor.getInstance().setExitVm(false);
      
        ShutdownMonitor.getInstance().setPort(0);
        TestableServer server = new TestableServer();
        server.setStopAtShutdown(true);
        server.start();
        
        //should be registered for shutdown on exit
        assertTrue(ShutdownThread.isRegistered(server));
        assertTrue(ShutdownMonitor.isRegistered(server));
        
        String key = ShutdownMonitor.getInstance().getKey();
        int port = ShutdownMonitor.getInstance().getPort();
        
        stop("stop", port, key, true);
        assertTrue(!ShutdownMonitor.getInstance().isAlive());
        assertTrue(server.stopped);
        assertTrue(!server.destroyed);
        assertTrue(!ShutdownThread.isRegistered(server));
        assertTrue(!ShutdownMonitor.isRegistered(server));
    }
    
    @Test
    public void testOldStopCommandWithStopOnShutdownFalse() throws Exception
    {
        //change so stopatshutdown is false, so stop does nothing in this case (as exitVm is false otherwise we couldn't run test)
        ShutdownMonitor.getInstance().setExitVm(false);
        System.setProperty("DEBUG", "true");
        ShutdownMonitor.getInstance().setPort(0);
        TestableServer server = new TestableServer();
        server.setStopAtShutdown(false);
        server.start();
        
        assertTrue(!ShutdownThread.isRegistered(server));
        assertTrue(ShutdownMonitor.isRegistered(server));
        
        String key = ShutdownMonitor.getInstance().getKey();
        int port = ShutdownMonitor.getInstance().getPort();
        
        stop ("stop", port, key, true);
        assertTrue(!ShutdownMonitor.getInstance().isAlive());
        assertTrue(!server.stopped);
        assertTrue(!server.destroyed);
        assertTrue(!ShutdownThread.isRegistered(server));
        assertTrue(ShutdownMonitor.isRegistered(server));
    }
    
    
  

    public void stop(String command, int port, String key, boolean check) throws Exception
    {
        System.out.printf("Attempting to send "+command+" to localhost:%d (%b)%n",port,check);
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"),port))
        {
            // send stop command
            try (OutputStream out = s.getOutputStream())
            {
                out.write((key + "\r\n"+command+"\r\n").getBytes());
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
