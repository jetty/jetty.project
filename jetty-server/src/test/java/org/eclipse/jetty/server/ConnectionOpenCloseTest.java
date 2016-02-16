//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class ConnectionOpenCloseTest extends AbstractHttpTest
{
    public ConnectionOpenCloseTest()
    {
        super(HttpVersion.HTTP_1_1.asString());
    }
    
    @Slow
    @Test
    public void testOpenClose() throws Exception
    {
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                throw new IllegalStateException();
            }
        });
        server.start();

        final AtomicInteger callbacks = new AtomicInteger();
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        connector.addBean(new Connection.Listener.Adapter()
        {
            @Override
            public void onOpened(Connection connection)
            {
                callbacks.incrementAndGet();
                openLatch.countDown();
            }

            @Override
            public void onClosed(Connection connection)
            {
                callbacks.incrementAndGet();
                closeLatch.countDown();
            }
        });

        try (Socket socket = new Socket("localhost", connector.getLocalPort());)
        {
            socket.setSoTimeout((int)connector.getIdleTimeout());

            Assert.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
            socket.shutdownOutput();
            Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
            String response=IO.toString(socket.getInputStream());
            Assert.assertEquals(0,response.length());

            // Wait some time to see if the callbacks are called too many times
            TimeUnit.MILLISECONDS.sleep(200);
            Assert.assertEquals(2, callbacks.get());
        }
    }
    
    @Slow
    @Test
    public void testOpenRequestClose() throws Exception
    {
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });
        server.start();

        final AtomicInteger callbacks = new AtomicInteger();
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        connector.addBean(new Connection.Listener.Adapter()
        {
            @Override
            public void onOpened(Connection connection)
            {
                callbacks.incrementAndGet();
                openLatch.countDown();
            }

            @Override
            public void onClosed(Connection connection)
            {
                callbacks.incrementAndGet();
                closeLatch.countDown();
            }
        });

        Socket socket = new Socket("localhost", connector.getLocalPort());
        socket.setSoTimeout((int)connector.getIdleTimeout());
        OutputStream output = socket.getOutputStream();
        output.write((
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        SimpleHttpResponse response = httpParser.readResponse(reader);
        Assert.assertEquals("200", response.getCode());

        Assert.assertEquals(-1, reader.read());
        socket.close();

        Assert.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));

        // Wait some time to see if the callbacks are called too many times
        TimeUnit.SECONDS.sleep(1);

        Assert.assertEquals(2, callbacks.get());
    }

    @Slow
    @Test
    public void testSSLOpenRequestClose() throws Exception
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        sslContextFactory.setKeyStoreResource(Resource.newResource(keystore));
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        server.addBean(sslContextFactory);

        server.removeConnector(connector);
        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });
        server.start();

        final AtomicInteger callbacks = new AtomicInteger();
        final CountDownLatch openLatch = new CountDownLatch(2);
        final CountDownLatch closeLatch = new CountDownLatch(2);
        connector.addBean(new Connection.Listener.Adapter()
        {
            @Override
            public void onOpened(Connection connection)
            {
                callbacks.incrementAndGet();
                openLatch.countDown();
            }

            @Override
            public void onClosed(Connection connection)
            {
                callbacks.incrementAndGet();
                closeLatch.countDown();
            }
        });

        Socket socket = sslContextFactory.getSslContext().getSocketFactory().createSocket("localhost", connector.getLocalPort());
        socket.setSoTimeout((int)connector.getIdleTimeout());
        OutputStream output = socket.getOutputStream();
        output.write(("" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        SimpleHttpResponse response = httpParser.readResponse(reader);
        Assert.assertEquals("200", response.getCode());

        Assert.assertEquals(-1, reader.read());
        socket.close();

        Assert.assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));

        // Wait some time to see if the callbacks are called too many times
        TimeUnit.SECONDS.sleep(1);

        Assert.assertEquals(4, callbacks.get());
    }
}
