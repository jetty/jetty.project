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

import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.IO;
import org.junit.Assert;
import org.junit.Test;

public abstract class ConnectorTimeoutTest extends HttpServerTestFixture
{

    @Test
    public void testSelectConnectorMaxIdleWithRequest() throws Exception
    {  
        /*
         * Test not working for Blocking connector
        configureServer(new EchoHandler());
        Socket client=new Socket(HOST,_connector.getLocalPort());

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
        
        String in = IO.toString(is);
        System.err.println(in);
         
        Thread.sleep(600);
        assertEquals(-1, is.read());
        */
    }
    
   

    @Test
    public void testSelectConnectorMaxIdleNoRequest() throws Exception
    {  
        /* Test is not working for Select and Blocking connectors - gregw to look at the SelectorManager and the idle timeout
        configureServer(new EchoHandler());
        Socket client=new Socket(HOST,_connector.getLocalPort());
        OutputStream os=client.getOutputStream();
        assertFalse(client.isClosed());
      
        Thread.sleep(1100);
        try
        { 
            os.write(("xx").getBytes("utf-8"));
            Assert.fail("Connection not closed");
        }
        catch (IOException e)
        {
            //expected result
        }
         */
    }  
   
}
