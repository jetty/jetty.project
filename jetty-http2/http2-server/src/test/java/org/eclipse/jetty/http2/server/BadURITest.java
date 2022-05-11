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

package org.eclipse.jetty.http2.server;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class BadURITest
{
    private Server server;
    private ServerConnector connector;

    protected void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1, new HTTP2CServerConnectionFactory(new HttpConfiguration()));
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testBadURI() throws Exception
    {
        CountDownLatch handlerLatch = new CountDownLatch(1);
        startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                jettyRequest.setHandled(true);
                handlerLatch.countDown();
            }
        });

        // Remove existing ErrorHandlers.
        for (ErrorHandler errorHandler : server.getBeans(ErrorHandler.class))
        {
            server.removeBean(errorHandler);
        }

        server.addBean(new ErrorHandler()
        {
            @Override
            public ByteBuffer badMessageError(int status, String reason, HttpFields fields)
            {
                // Return a very large buffer that will cause HTTP/2 flow control exhaustion and/or TCP congestion.
                return ByteBuffer.allocateDirect(128 * 1024 * 1024);
            }
        });

        ByteBufferPool byteBufferPool = connector.getByteBufferPool();
        Generator generator = new Generator(byteBufferPool);

        // Craft a request with a bad URI, it will not hit the Handler.
        MetaData.Request metaData1 = new MetaData.Request(
            HttpMethod.GET.asString(),
            HttpScheme.HTTP.asString(),
            new HostPortHttpField("localhost:" + connector.getLocalPort()),
            // Use an ambiguous path parameter so that the URI is invalid.
            "/foo/..;/bar",
            HttpVersion.HTTP_2,
            new HttpFields(),
            -1
        );
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        generator.control(lease, new HeadersFrame(1, metaData1, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            // Wait for the first request be processed on the server.
            Thread.sleep(1000);

            // Send a second request and verify that it hits the Handler.
            lease.recycle();
            MetaData.Request metaData2 = new MetaData.Request(
                HttpMethod.GET.asString(),
                HttpScheme.HTTP.asString(),
                new HostPortHttpField("localhost:" + connector.getLocalPort()),
                "/valid",
                HttpVersion.HTTP_2,
                new HttpFields(),
                -1
            );
            generator.control(lease, new HeadersFrame(3, metaData2, null, true));
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }
            assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));
        }
    }
}
