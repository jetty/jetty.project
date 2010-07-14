// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import junit.framework.Assert;

import org.eclipse.jetty.util.IO;
import org.junit.Test;

public abstract class ConnectorTimeoutTest extends HttpServerTestFixture
{
    static
    {
        System.setProperty("org.mortbay.io.nio.IDLE_TICK","100");
    }
    
    
    @Test
    public void testSelectConnectorMaxIdleWithRequest() throws Exception
    {  
        configureServer(new EchoHandler());
        Socket client=newSocket(HOST,_connector.getLocalPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());
        
        OutputStream os=client.getOutputStream();
        InputStream is=client.getInputStream();

        String content="Wibble";
        byte[] contentB=content.getBytes("utf-8");
        os.write((
                "POST /echo HTTP/1.1\r\n"+
                "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                "content-type: text/plain; charset=utf-8\r\n"+
                "content-length: "+contentB.length+"\r\n"+
        "\r\n").getBytes("utf-8"));
        os.write(contentB);

        long start = System.currentTimeMillis();
        String in = IO.toString(is);
        System.err.println(in);
         
        Thread.sleep(300);
        assertEquals(-1, is.read());

        Assert.assertTrue(System.currentTimeMillis()-start>200);
        Assert.assertTrue(System.currentTimeMillis()-start<1000);
    }
    

    @Test
    public void testSelectConnectorMaxIdleNoRequest() throws Exception
    {  
        configureServer(new EchoHandler());
        Socket client=newSocket(HOST,_connector.getLocalPort());
        client.setSoTimeout(10000);
        InputStream is=client.getInputStream();
        assertFalse(client.isClosed());
      
        Thread.sleep(500);
        long start = System.currentTimeMillis();
        try
        {
            IO.toString(is);
            assertEquals(-1, is.read());
        }
        catch(Exception e)
        {
            // SSL throws
        }
        Assert.assertTrue(System.currentTimeMillis()-start<200);
        
        
    }  
   
}
