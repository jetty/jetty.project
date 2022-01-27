//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectionOpenCloseTest extends AbstractHttpTest
{
    @Test
    public void testOpenClose() throws Exception
    {
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
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

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            socket.setSoTimeout((int)connector.getIdleTimeout());

            assertTrue(openLatch.await(5, TimeUnit.SECONDS));
            socket.shutdownOutput();
            assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
            String response = IO.toString(socket.getInputStream());
            assertEquals(0, response.length());

            // Wait some time to see if the callbacks are called too many times
            TimeUnit.MILLISECONDS.sleep(200);
            assertEquals(2, callbacks.get());
        }
    }

    @Test
    public void testOpenRequestClose() throws Exception
    {
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
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

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            socket.setSoTimeout((int)connector.getIdleTimeout());
            OutputStream output = socket.getOutputStream();
            output.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream inputStream = socket.getInputStream();
            HttpTester.Response response = HttpTester.parseResponse(inputStream);
            assertThat("Status Code", response.getStatus(), is(200));

            assertEquals(-1, inputStream.read());
            socket.close();

            assertTrue(openLatch.await(5, TimeUnit.SECONDS));
            assertTrue(closeLatch.await(5, TimeUnit.SECONDS));

            // Wait some time to see if the callbacks are called too many times
            TimeUnit.MILLISECONDS.sleep(200);

            assertEquals(2, callbacks.get());
        }
    }

    @Test
    public void testSSLOpenRequestClose() throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        File keystore = MavenTestingUtils.getTestResourceFile("keystore.p12");
        sslContextFactory.setKeyStoreResource(Resource.newResource(keystore));
        sslContextFactory.setKeyStorePassword("storepwd");
        server.addBean(sslContextFactory);

        server.removeConnector(connector);
        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
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
        output.write((
            "GET / HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();

        // Read to EOF
        String response = BufferUtil.toString(ByteBuffer.wrap(IO.readBytes(socket.getInputStream())));
        assertThat(response, Matchers.containsString("200 OK"));
        socket.close();

        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));

        // Wait some time to see if the callbacks are called too many times
        TimeUnit.MILLISECONDS.sleep(200);

        assertEquals(4, callbacks.get());
    }
}
