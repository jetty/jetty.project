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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import javax.net.ssl.SSLException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.Assert;

import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.junit.Test;

public abstract class ConnectorTimeoutTest extends HttpServerTestFixture
{
    protected static final int MAX_IDLE_TIME=250;
    
    static
    {
        System.setProperty("org.eclipse.jetty.io.nio.IDLE_TICK","100");
    }
    
    
    @Test
    public void testMaxIdleWithRequest10() throws Exception
    {  
        configureServer(new HelloWorldHandler());
        Socket client=newSocket(HOST,_connector.getLocalPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());
        
        OutputStream os=client.getOutputStream();
        InputStream is=client.getInputStream();

        os.write((
                "GET / HTTP/1.0\r\n"+
                "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                "connection: keep-alive\r\n"+
        "\r\n").getBytes("utf-8"));
        os.flush();

        long start = System.currentTimeMillis();
        IO.toString(is);
         
        Thread.sleep(300);
        assertEquals(-1, is.read());

        Assert.assertTrue(System.currentTimeMillis()-start>200);
        Assert.assertTrue(System.currentTimeMillis()-start<5000);
    }

    @Test
    public void testMaxIdleWithRequest11() throws Exception
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
        os.flush();

        long start = System.currentTimeMillis();
        IO.toString(is);
         
        Thread.sleep(300);
        assertEquals(-1, is.read());

        Assert.assertTrue(System.currentTimeMillis()-start>200);
        Assert.assertTrue(System.currentTimeMillis()-start<5000);
    }
    

    @Test
    public void testMaxIdleNoRequest() throws Exception
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
        catch(SSLException e)
        {
            
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
        Assert.assertTrue(System.currentTimeMillis()-start<5000);
        
    }  

    @Test
    public void testMaxIdleWithSlowRequest() throws Exception
    {  
        configureServer(new EchoHandler());
        Socket client=newSocket(HOST,_connector.getLocalPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());
        
        OutputStream os=client.getOutputStream();
        InputStream is=client.getInputStream();

        String content="Wibble\r\n";
        byte[] contentB=content.getBytes("utf-8");
        os.write((
                "GET / HTTP/1.0\r\n"+
                "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                "connection: keep-alive\r\n"+
                "Content-Length: "+(contentB.length*20)+"\r\n"+
                "Content-Type: text/plain\r\n"+
                "Connection: close\r\n"+
        "\r\n").getBytes("utf-8"));
        os.flush();

        for (int i =0;i<20;i++)
        {
            Thread.sleep(50);
            os.write(contentB);
            os.flush();
        }
        
        String in = IO.toString(is);
        int offset=0;
        for (int i =0;i<20;i++)
        {
            offset=in.indexOf("Wibble",offset+1);
            Assert.assertTrue(""+i,offset>0);
        }
    }

    @Test
    public void testMaxIdleWithSlowResponse() throws Exception
    {  
        configureServer(new SlowResponseHandler());
        Socket client=newSocket(HOST,_connector.getLocalPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());
        
        OutputStream os=client.getOutputStream();
        InputStream is=client.getInputStream();

        os.write((
                "GET / HTTP/1.0\r\n"+
                "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                "connection: keep-alive\r\n"+
                "Connection: close\r\n"+
        "\r\n").getBytes("utf-8"));
        os.flush();
        
        String in = IO.toString(is);
        int offset=0;
        for (int i =0;i<20;i++)
        {
            offset=in.indexOf("Hello World",offset+1);
            Assert.assertTrue(""+i,offset>0);
        }
    }

    @Test
    public void testMaxIdleWithWait() throws Exception
    {  
        configureServer(new WaitHandler());
        Socket client=newSocket(HOST,_connector.getLocalPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());
        
        OutputStream os=client.getOutputStream();
        InputStream is=client.getInputStream();

        os.write((
                "GET / HTTP/1.0\r\n"+
                "host: "+HOST+":"+_connector.getLocalPort()+"\r\n"+
                "connection: keep-alive\r\n"+
                "Connection: close\r\n"+
        "\r\n").getBytes("utf-8"));
        os.flush();
        
        String in = IO.toString(is);
        int offset=in.indexOf("Hello World");
        Assert.assertTrue(offset>0);
    }
    
    protected static class SlowResponseHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            OutputStream out = response.getOutputStream();
            
            for (int i=0;i<20;i++)
            {
                out.write("Hello World\r\n".getBytes());
                out.flush();
                try{Thread.sleep(50);}catch(Exception e){e.printStackTrace();}
            }
            out.close();
        }
    }
    
    protected static class WaitHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            OutputStream out = response.getOutputStream();
            try{Thread.sleep(2000);}catch(Exception e){e.printStackTrace();}
            out.write("Hello World\r\n".getBytes());
            out.flush();
        }
    }
}
