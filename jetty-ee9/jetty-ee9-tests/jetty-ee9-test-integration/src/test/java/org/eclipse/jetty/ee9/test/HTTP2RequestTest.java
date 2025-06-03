//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.test;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP2RequestTest
{
    private Server server;
    private ServerConnector connector;

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(server);
    }

    public void start(HttpServlet servlet) throws Exception
    {
        server = new Server();

        connector = new ServerConnector(server, new HTTP2ServerConnectionFactory());
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.addServlet(new ServletHolder(servlet), "/*");
        server.setHandler(contextHandler);

        server.start();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%00", "%7="})
    public void testBadRequestPathReturnsResponseViaErrorHandler(String badPath) throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                // Should not be called.
                response.setStatus(200);
            }
        });

        CountDownLatch errorHandlerLatch = new CountDownLatch(1);
        server.setErrorHandler(new ErrorHandler()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) throws Exception
            {
                errorHandlerLatch.countDown();
                return super.handle(request, response, callback);
            }
        });

        try (HTTP2Client http2Client = new HTTP2Client())
        {
            http2Client.start();

            InetSocketAddress address = new InetSocketAddress("localhost", connector.getLocalPort());
            Session session = http2Client.connect(address, new Session.Listener() {}).get(5, TimeUnit.SECONDS);

            // Craft a request with a bad URI, it will not hit the Servlet.
            MetaData.Request metaData = new MetaData.Request(
                HttpMethod.GET.asString(),
                new HttpURI.Unsafe(HttpScheme.HTTPS.asString(), "localhost", connector.getLocalPort(), badPath, null, null),
                HttpVersion.HTTP_3,
                HttpFields.EMPTY
            );

            AtomicReference<MetaData.Response> responseRef = new AtomicReference<>();
            CountDownLatch responseLatch = new CountDownLatch(1);
            CountDownLatch failureLatch = new CountDownLatch(1);
            session.newStream(new HeadersFrame(metaData, null, true), new Stream.Listener()
            {
                @Override
                public void onHeaders(Stream stream, HeadersFrame frame)
                {
                    responseRef.set((MetaData.Response)frame.getMetaData());
                    if (frame.isEndStream())
                        responseLatch.countDown();
                    else
                        stream.demand();
                }

                @Override
                public void onDataAvailable(Stream stream)
                {
                    Stream.Data data = stream.readData();
                    if (data == null)
                    {
                        stream.demand();
                        return;
                    }
                    data.release();
                    if (data.frame().isEndStream())
                        responseLatch.countDown();
                    else
                        stream.demand();
                }

                @Override
                public void onReset(Stream stream, ResetFrame frame, Callback callback)
                {
                    failureLatch.countDown();
                }

                @Override
                public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
                {
                    failureLatch.countDown();
                }
            });

            assertTrue(errorHandlerLatch.await(5, TimeUnit.SECONDS));
            assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
            MetaData.Response response = responseRef.get();
            assertThat(response.getStatus(), Matchers.is(HttpStatus.BAD_REQUEST_400));
            assertFalse(failureLatch.await(1, TimeUnit.SECONDS));
        }
    }
}
