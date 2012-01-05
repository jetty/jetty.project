/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpServletRequest;

import junit.framework.Assert;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebSocketMessageD00Test
{
    private static Server __server;
    private static Connector __connector;
    private static TestWebSocket __serverWebSocket;
    private static CountDownLatch __latch;
    private static AtomicInteger __textCount = new AtomicInteger(0);


    @BeforeClass
    public static void startServer() throws Exception
    {
        __server = new Server();
        __connector = new SelectChannelConnector();
        __server.addConnector(__connector);
        WebSocketHandler wsHandler = new WebSocketHandler()
        {
            public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                __serverWebSocket = new TestWebSocket();
                __serverWebSocket._onConnect=("onConnect".equals(protocol)); 
                __serverWebSocket._echo=("echo".equals(protocol));
                __serverWebSocket._latch=("latch".equals(protocol));
                if (__serverWebSocket._latch)
                    __latch=new CountDownLatch(1);
                return __serverWebSocket;
            }
        };
        wsHandler.setHandler(new DefaultHandler());
        __server.setHandler(wsHandler);
        __server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        __server.stop();
        __server.join();
    }

    @Before
    public void reset()
    {
        __textCount.set(0);
    }
    
    @Test
    public void testServerSendBigStringMessage() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                "\r\n"+
                "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "ISO-8859-1"));
        String responseLine = reader.readLine();
        assertTrue(responseLine.startsWith("HTTP/1.1 101 WebSocket Protocol Handshake"));
        // Read until we find an empty line, which signals the end of the http response
        String line;
        while ((line = reader.readLine()) != null)
            if (line.length() == 0)
                break;
        
        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.outbound);
        
        // read the hixie bytes
        char[] hixie=new char[16]; // should be bytes, but we know this example is all ascii
        int h=16;
        int o=0;
        do 
        {
            int l=reader.read(hixie,o,h);
            if (l<0)
                break;
            h-=l;
            o+=l;
        }
        while (h>0);
        assertEquals("8jKS'y:G*Co,Wxa-",new String(hixie,0,16));
        
        // Server sends a big message
        StringBuilder message = new StringBuilder();
        String text = "0123456789ABCDEF";
        for (int i = 0; i < 64 * 1024 / text.length(); ++i)
            message.append(text);
        __serverWebSocket.outbound.sendMessage(message.toString());

        // Read until we get 0xFF
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (true)
        {
            int read = input.read();
            baos.write(read);
            if (read == 0xFF)
                break;
        }
        baos.close();
        byte[] bytes = baos.toByteArray();
        String result = StringUtil.printable(bytes);
        assertTrue(result.startsWith("0x00"));
        assertTrue(result.endsWith("0xFF"));
        assertEquals(message.length() + "0x00".length() + "0xFF".length(), result.length());
    }

    @Test
    public void testServerSendOnConnect() throws Exception
    {
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Protocol: onConnect\r\n" +
                "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                "\r\n"+
                "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        
        String looking_for="HTTP/1.1 101 WebSocket Protocol Handshake\r\n";

        while(true)
        {
            int b = input.read();
            if (b<0)
                throw new EOFException();

            assertEquals((int)looking_for.charAt(0),b);
            if (looking_for.length()==1)
                break;
            looking_for=looking_for.substring(1);
        }

        String skipping_for="\r\n\r\n";
        int state=0;

        while(true)
        {
            int b = input.read();
            if (b<0)
                throw new EOFException();

            if (b==skipping_for.charAt(state))
            {
                state++;
                if (state==skipping_for.length())
                    break;
            }
            else
                state=0;
        }
        

        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.outbound);
        
        looking_for="8jKS'y:G*Co,Wxa-";
        while(true)
        {
            int b = input.read();
            if (b<0)
                throw new EOFException();

            assertEquals((int)looking_for.charAt(0),b);
            if (looking_for.length()==1)
                break;
            looking_for=looking_for.substring(1);
        }
        
        assertEquals(0x00,input.read());
        looking_for="sent on connect";
        while(true)
        {
            int b = input.read();
            if (b<0)
                throw new EOFException();

            assertEquals((int)looking_for.charAt(0),b);
            if (looking_for.length()==1)
                break;
            looking_for=looking_for.substring(1);
        }
        assertEquals(0xff,input.read());
    }

    

    @Test
    public void testServerEcho() throws Exception
    {
        // Log.getLogger("org.eclipse.jetty.websocket").setDebugEnabled(true);
        
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        socket.setSoTimeout(1000); 
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Upgrade: WebSocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Protocol: echo\r\n" +
                        "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                        "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                        "\r\n"+
                        "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);
        lookFor("8jKS'y:G*Co,Wxa-",input);
        
        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        
        
        output.write(0x00);
        byte[] bytes="this is an echo".getBytes(StringUtil.__ISO_8859_1);
        for (int i=0;i<bytes.length;i++)
            output.write(bytes[i]);
        output.write(0xff);
        output.flush();
        
        assertEquals("00",TypeUtil.toHexString((byte)(0xff&input.read())));
        lookFor("this is an echo",input);
        assertEquals(0xff,input.read());
    }
    
    @Test
    public void testBlockedConsumer() throws Exception
    {

        // Log.getLogger("org.eclipse.jetty.websocket").setDebugEnabled(true);
        
        Socket socket = new Socket("localhost", __connector.getLocalPort());
        socket.setSoTimeout(60000); 
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Upgrade: WebSocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Protocol: latch\r\n" +
                        "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                        "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                        "\r\n"+
                        "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);
        lookFor("8jKS'y:G*Co,Wxa-",input);
        
        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        __serverWebSocket.connection.setMaxIdleTime(60000);
        
        
        byte[] bytes="This is a long message of text that we will send again and again".getBytes(StringUtil.__ISO_8859_1);
        byte[] mesg=new byte[bytes.length+2];
        mesg[0]=(byte)0x00;
        for (int i=0;i<bytes.length;i++)
            mesg[i+1]=(byte)(bytes[i]);
        mesg[mesg.length-1]=(byte)0xFF;
        
        final int count = 100000;


        // Send and receive 1 message
        output.write(mesg);
        output.flush();
        while(__textCount.get()==0)
            Thread.sleep(10);

        // unblock the latch in 4s
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Thread.sleep(4000);
                    __latch.countDown();
                    //System.err.println("latched");
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();
        
        // Send enough messages to fill receive buffer
        long max=0;
        long start=System.currentTimeMillis();
        for (int i=0;i<count;i++)
        {
            output.write(mesg);
            if (i%100==0)
            {
                // System.err.println(">>> "+i);
                output.flush();
                
                long now=System.currentTimeMillis();
                long duration=now-start;
                start=now;
                if (max<duration)
                    max=duration;
            }
        }

        Thread.sleep(50);
        while(__textCount.get()<count+1)
        {
            System.err.println(__textCount.get()+"<"+(count+1));
            Thread.sleep(10);
        }
        assertEquals(count+1,__textCount.get()); // all messages
        assertTrue(max>2000); // was blocked
    }
    
    @Test
    public void testBlockedProducer() throws Exception
    {
        // Log.getLogger("org.eclipse.jetty.websocket").setDebugEnabled(true);
        
        final Socket socket = new Socket("localhost", __connector.getLocalPort());
        socket.setSoTimeout(60000); 
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Upgrade: WebSocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Protocol: latch\r\n" +
                        "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                        "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                        "\r\n"+
                        "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);
        lookFor("8jKS'y:G*Co,Wxa-",input);
        
        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        
        
        final int count = 100000;

        __serverWebSocket.connection.setMaxIdleTime(60000);
        __latch.countDown();

        // wait 2s and then consume messages
        final AtomicLong totalB=new AtomicLong();
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Thread.sleep(2000);

                    byte[] recv = new byte[32*1024];

                    int len=0;
                    while (len>=0)
                    {
                        totalB.addAndGet(len);
                        len=socket.getInputStream().read(recv,0,recv.length);
                        Thread.sleep(10);
                    }
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();
        
        
        // Send enough messages to fill receive buffer
        long max=0;
        long start=System.currentTimeMillis();
        String mesg="How Now Brown Cow";
        for (int i=0;i<count;i++)
        {
            __serverWebSocket.connection.sendMessage(mesg);
            if (i%100==0)
            {
                output.flush();
                
                long now=System.currentTimeMillis();
                long duration=now-start;
                start=now;
                if (max<duration)
                    max=duration;
            }
        }
        
        while(totalB.get()<(count*(mesg.length()+2)))
            Thread.sleep(100);
        
        assertEquals(count*(mesg.length()+2),totalB.get()); // all messages
        assertTrue(max>1000); // was blocked
    }



    @Test
    public void testIdle() throws Exception
    {
        // Log.getLogger("org.eclipse.jetty.websocket").setDebugEnabled(true);
        
        final Socket socket = new Socket("localhost", __connector.getLocalPort());
        socket.setSoTimeout(10000); 
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Upgrade: WebSocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Protocol: onConnect\r\n" +
                        "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                        "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                        "\r\n"+
                        "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);
        lookFor("8jKS'y:G*Co,Wxa-",input);
        
        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        __serverWebSocket.connection.setMaxIdleTime(250);
        assertEquals(0x00,input.read());
        lookFor("sent on connect",input);
        assertEquals(0xff,input.read());

        assertEquals(-1,input.read());
        socket.close();
        
        assertTrue(__serverWebSocket.awaitDisconnected(100));
    }
    
    @Test
    public void testIdleBadClient() throws Exception
    {
        // Log.getLogger("org.eclipse.jetty.websocket").setDebugEnabled(true);
        
        final Socket socket = new Socket("localhost", __connector.getLocalPort());
        socket.setSoTimeout(10000); 
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Upgrade: WebSocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Protocol: onConnect\r\n" +
                        "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                        "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                        "\r\n"+
                        "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);
        lookFor("8jKS'y:G*Co,Wxa-",input);
        
        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        __serverWebSocket.connection.setMaxIdleTime(250);
        assertEquals(0x00,input.read());
        lookFor("sent on connect",input);
        assertEquals(0xff,input.read());

        assertEquals(-1,input.read());
        
        assertTrue(__serverWebSocket.disconnected.getCount()>0);
        assertTrue(__serverWebSocket.awaitDisconnected(1000));
    }

    @Test
    public void testTCPClose() throws Exception
    {
        // Log.getLogger("org.eclipse.jetty.websocket").setDebugEnabled(true);
        
        final Socket socket = new Socket("localhost", __connector.getLocalPort());
        socket.setSoTimeout(10000); 
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Upgrade: WebSocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Protocol: onConnect\r\n" +
                        "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                        "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                        "\r\n"+
                        "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);
        lookFor("8jKS'y:G*Co,Wxa-",input);
        
        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        __serverWebSocket.connection.setMaxIdleTime(250);
        assertEquals(0x00,input.read());
        lookFor("sent on connect",input);
        assertEquals(0xff,input.read());
        
        
        
        socket.close();
        
        assertTrue(__serverWebSocket.awaitDisconnected(500));

        try
        {
            __serverWebSocket.connection.sendMessage("Don't send");
            assertTrue(false);
        }
        catch(IOException e)
        {
            assertTrue(true);
        }    
    }

    @Test
    public void testTCPHalfClose() throws Exception
    {
        // Log.getLogger("org.eclipse.jetty.websocket").setDebugEnabled(true);
        
        final Socket socket = new Socket("localhost", __connector.getLocalPort());
        socket.setSoTimeout(10000); 
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Upgrade: WebSocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Protocol: onConnect\r\n" +
                        "Sec-WebSocket-Key1: 4 @1  46546xW%0l 1 5\r\n" +
                        "Sec-WebSocket-Key2: 12998 5 Y3 1  .P00\r\n" +
                        "\r\n"+
                        "^n:ds[4U").getBytes("ISO-8859-1"));
        output.flush();
        
        lookFor("HTTP/1.1 101 WebSocket Protocol Handshake\r\n",input);
        skipTo("\r\n\r\n",input);
        lookFor("8jKS'y:G*Co,Wxa-",input);
        
        assertTrue(__serverWebSocket.awaitConnected(1000));
        assertNotNull(__serverWebSocket.connection);
        __serverWebSocket.connection.setMaxIdleTime(250);
        assertEquals(0x00,input.read());
        lookFor("sent on connect",input);
        assertEquals(0xff,input.read());
        
        
        socket.shutdownOutput();
        
        assertTrue(__serverWebSocket.awaitDisconnected(500));

        assertEquals(-1,input.read());
        
        // look for broken pipe
        try
        {
            for (int i=0;i<1000;i++)
                output.write(0);
            Assert.fail();
        }
        catch(SocketException e)
        {
            // expected
        }
    }
    
    
    
    
    
    
    
    
    
    
    
    

    private void lookFor(String string,InputStream in)
        throws IOException
    {
        String orig=string;
        Utf8StringBuilder scanned=new Utf8StringBuilder();
        try
        {
            while(true)
            {
                int b = in.read();
                if (b<0)
                    throw new EOFException();
                scanned.append((byte)b);
                assertEquals("looking for\""+orig+"\" in '"+scanned+"'",(int)string.charAt(0),b);
                if (string.length()==1)
                    break;
                string=string.substring(1);
            }
        }
        catch(IOException e)
        {
            System.err.println("IOE while looking for \""+orig+"\" in '"+scanned+"'");
            throw e;
        }
    }

    private void skipTo(String string,InputStream in)
    throws IOException
    {
        int state=0;

        while(true)
        {
            int b = in.read();
            if (b<0)
                throw new EOFException();

            if (b==string.charAt(state))
            {
                state++;
                if (state==string.length())
                    break;
            }
            else
                state=0;
        }
    }
    
    

    private static class TestWebSocket implements WebSocket.OnFrame, WebSocket, WebSocket.OnTextMessage
    {
        protected boolean _latch;
        boolean _echo=true;
        boolean _onConnect=false;
        private volatile Connection outbound;
        private final CountDownLatch connected = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private volatile FrameConnection connection;

        public FrameConnection getConnection()
        {
            return connection;
        }
        
        public void onHandshake(FrameConnection connection)
        {
            this.connection = connection;
        }
        
        public void onOpen(Connection outbound)
        {
            this.outbound = outbound;
            if (_onConnect)
            {
                try
                {
                    outbound.sendMessage("sent on connect");
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
            connected.countDown();
        }

        private boolean awaitConnected(long time) throws InterruptedException
        {
            return connected.await(time, TimeUnit.MILLISECONDS);
        }

        private boolean awaitDisconnected(long time) throws InterruptedException
        {
            return disconnected.await(time, TimeUnit.MILLISECONDS);
        }
        
        public void onClose(int code,String message)
        {
            disconnected.countDown();
        }

        public boolean onFrame(byte flags, byte opcode, byte[] data, int offset, int length)
        {
            return true;
        }

        public void onMessage(String data)
        {
            __textCount.incrementAndGet();
            if (_latch)
            {
                try
                {
                    __latch.await();
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
            
            if (_echo)
            {
                try
                {
                    outbound.sendMessage(data); 
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }
}
