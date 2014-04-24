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

package org.eclipse.jetty.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpClientDuplexTest
{
    private ServerSocket server;
    private HttpClient client;

    @Before
    public void prepare() throws Exception
    {
        server = new ServerSocket(0);

        client = new HttpClient();
        client.start();
    }

    @After
    public void dispose() throws Exception
    {
        client.stop();
        server.close();
    }

    @Test
    public void testResponseHeadersBeforeRequestContent() throws Exception
    {
        final byte[] chunk1 = new byte[]{'A'};
        final byte[] chunk2 = new byte[]{'B'};
        final CountDownLatch requestContentLatch = new CountDownLatch(1);
        ContentExchange exchange = new ContentExchange(true)
        {
            private int chunks;

            @Override
            public Buffer getRequestContentChunk(Buffer buffer) throws IOException
            {
                ++chunks;
                if (chunks == 1)
                {
                    if (!await(requestContentLatch, 5000))
                        throw new IOException();
                    return new ByteArrayBuffer(chunk1);
                }
                else if (chunks == 2)
                {
                    // The test needs a second chunk to stay in "sending"
                    // state and trigger the condition we want to test.
                    return new ByteArrayBuffer(chunk2);
                }
                else
                {
                    return null;
                }
            }
        };
        exchange.setURL("http://localhost:" + server.getLocalPort());
        // The test needs a fake content source to invoke
        // getRequestContentChunk() which will provide the content.
        exchange.setRequestContentSource(new ClosedInputStream());
        exchange.setRequestHeader("Content-Length", String.valueOf(chunk1.length + chunk2.length));
        client.send(exchange);

        Socket socket = server.accept();
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        OutputStream output = socket.getOutputStream();

        // Read headers.
        while (true)
        {
            String line = input.readLine();
            Assert.assertNotNull(line);
            if (line.length() == 0)
                break;
        }

        byte[] responseContent = new byte[64];
        String responseHeaders = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: " + responseContent.length + "\r\n" +
                "\r\n";
        output.write(responseHeaders.getBytes("UTF-8"));
        output.flush();

        // Now send more request content.
        requestContentLatch.countDown();

        // Read request content on server.
        for (int i = 0; i < chunk1.length; ++i)
            Assert.assertNotEquals(-1, input.read());
        for (int i = 0; i < chunk2.length; ++i)
            Assert.assertNotEquals(-1, input.read());

        // Send response content to client.
        output.write(responseContent);
        output.flush();

        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
        Assert.assertEquals(200, exchange.getResponseStatus());

        socket.close();
    }

    @Test
    public void testResponseHeadersBeforeRequestContentThenExpire() throws Exception
    {
        final byte[] chunk1 = new byte[]{'A'};
        final byte[] chunk2 = new byte[]{'B'};
        final byte[] chunk3 = new byte[]{'C'};
        final CountDownLatch requestContentLatch = new CountDownLatch(1);
        final long idleTimeout = 1000;
        ContentExchange exchange = new ContentExchange(true)
        {
            private int chunks;

            @Override
            public Buffer getRequestContentChunk(Buffer buffer) throws IOException
            {
                ++chunks;
                if (chunks == 1)
                {
                    if (!await(requestContentLatch, 5000))
                        throw new IOException();
                    return new ByteArrayBuffer(chunk1);
                }
                else if (chunks == 2)
                {
                    // The test needs a second chunk to stay in "sending"
                    // state and trigger the condition we want to test.
                    return new ByteArrayBuffer(chunk2);
                }
                else if (chunks == 3)
                {
                    // Idle timeout.
                    await(new CountDownLatch(1), idleTimeout * 2);
                }
                return null;
            }
        };
        exchange.setURL("http://localhost:" + server.getLocalPort());
        // The test needs a fake content source to invoke
        // getRequestContentChunk() which will provide the content.
        exchange.setRequestContentSource(new ClosedInputStream());
        exchange.setRequestHeader("Content-Length", String.valueOf(chunk1.length + chunk2.length + chunk3.length));
        exchange.setTimeout(idleTimeout);
        client.send(exchange);

        Socket socket = server.accept();
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        OutputStream output = socket.getOutputStream();

        // Read headers.
        while (true)
        {
            String line = input.readLine();
            Assert.assertNotNull(line);
            if (line.length() == 0)
                break;
        }

        byte[] responseContent = new byte[64];
        String responseHeaders = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: " + responseContent.length + "\r\n" +
                "\r\n";
        output.write(responseHeaders.getBytes("UTF-8"));
        output.flush();

        // Now try to send more request content, but it will timeout.
        requestContentLatch.countDown();

        // Read request content on server.
        for (int i = 0; i < chunk1.length; ++i)
            Assert.assertNotEquals(-1, input.read());
        // Server could possibly read -1.
        for (int i = 0; i < chunk2.length; ++i)
            input.read();
        for (int i = 0; i < chunk3.length; ++i)
            input.read();

        // Send response content to client.
        output.write(responseContent);
        output.flush();

        Assert.assertEquals(HttpExchange.STATUS_EXPIRED, exchange.waitForDone());

        socket.close();
    }

    @Test
    public void testResponseHeadersBeforeRequestContentThenThrow() throws Exception
    {
        final byte[] chunk1 = new byte[]{'A'};
        final byte[] chunk2 = new byte[]{'B'};
        final CountDownLatch requestContentLatch = new CountDownLatch(1);
        ContentExchange exchange = new ContentExchange(true)
        {
            private int chunks;

            @Override
            public Buffer getRequestContentChunk(Buffer buffer) throws IOException
            {
                ++chunks;
                if (chunks == 1)
                {
                    if (!await(requestContentLatch, 5000))
                        throw new IOException();
                    return new ByteArrayBuffer(chunk1);
                }
                else if (chunks == 2)
                {
                    // The test needs a second chunk to stay in "sending"
                    // state and trigger the condition we want to test.
                    return new ByteArrayBuffer(chunk2);
                }
                else
                {
                    throw new IOException();
                }
            }
        };
        exchange.setURL("http://localhost:" + server.getLocalPort());
        // The test needs a fake content source to invoke
        // getRequestContentChunk() which will provide the content.
        exchange.setRequestContentSource(new ClosedInputStream());
        exchange.setRequestHeader("Content-Length", String.valueOf(chunk1.length + chunk2.length));
        client.send(exchange);

        Socket socket = server.accept();
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        OutputStream output = socket.getOutputStream();

        // Read headers.
        while (true)
        {
            String line = input.readLine();
            Assert.assertNotNull(line);
            if (line.length() == 0)
                break;
        }

        byte[] responseContent = new byte[64];
        String responseHeaders = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: " + responseContent.length + "\r\n" +
                "\r\n";
        output.write(responseHeaders.getBytes("UTF-8"));
        output.flush();

        // Now send more request content.
        requestContentLatch.countDown();

        // Read request content on server.
        for (int i = 0; i < chunk1.length; ++i)
            Assert.assertNotEquals(-1, input.read());
        // Server could possibly read -1.
        for (int i = 0; i < chunk2.length; ++i)
            input.read();

        // Send response content to client.
        output.write(responseContent);
        output.flush();

        Assert.assertEquals(HttpExchange.STATUS_EXCEPTED, exchange.waitForDone());

        socket.close();
    }


    @Test
    public void testResponseCompleteBeforeRequestContent() throws Exception
    {
        // Must be greater than 2 to stay in "sending" state while
        // receiving the response and trigger the condition of this test.
        int contentLength = 4;
        final byte[] chunk = new byte[]{'A'};
        final AtomicInteger requestContent = new AtomicInteger(contentLength);
        ContentExchange exchange = new ContentExchange(true)
        {
            @Override
            public Buffer getRequestContentChunk(Buffer buffer) throws IOException
            {
                if (requestContent.decrementAndGet() > 0)
                    return new ByteArrayBuffer(chunk);
                return null;
            }
        };
        exchange.setURL("http://localhost:" + server.getLocalPort());
        // The test needs a fake content source to invoke
        // getRequestContentChunk() which will provide the content.
        exchange.setRequestContentSource(new ClosedInputStream());
        exchange.setRequestHeader("Content-Length", String.valueOf(contentLength));
        client.send(exchange);

        Socket socket = server.accept();
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        OutputStream output = socket.getOutputStream();

        // Read headers.
        while (true)
        {
            String line = input.readLine();
            Assert.assertNotNull(line);
            if (line.length() == 0)
                break;
        }

        // Send the whole response.
        String responseHeaders = "" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";
        output.write(responseHeaders.getBytes("UTF-8"));
        output.flush();

        // Read request content on server.
        while (true)
        {
            if (input.read() < 0)
                break;
        }

        Assert.assertEquals(HttpExchange.STATUS_COMPLETED, exchange.waitForDone());
        Assert.assertEquals(200, exchange.getResponseStatus());

        socket.close();
    }

    private boolean await(CountDownLatch latch, long millis) throws InterruptedIOException
    {
        try
        {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
    }

    private class ClosedInputStream extends InputStream
    {
        @Override
        public int read() throws IOException
        {
            return -1;
        }
    }
}
