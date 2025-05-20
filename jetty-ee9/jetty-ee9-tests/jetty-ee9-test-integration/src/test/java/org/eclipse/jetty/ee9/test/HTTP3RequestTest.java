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
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class HTTP3RequestTest
{
    public WorkDir workDir;
    private Path pemDir;
    private Server server;
    private QuicServerConnector connector;

    @BeforeEach
    public void prepare()
    {
        pemDir = workDir.getEmptyPathDir();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    public void start(HttpServlet servlet) throws Exception
    {
        server = new Server();

        SslContextFactory.Server ssl = new SslContextFactory.Server();
        ssl.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        ssl.setKeyStorePassword("storepwd");
        Path serverPemDirectory = Files.createDirectories(pemDir.resolve("server"));
        ServerQuicConfiguration serverQuicConfig = new ServerQuicConfiguration(ssl, serverPemDirectory);
        connector = new QuicServerConnector(server, serverQuicConfig, new HTTP3ServerConnectionFactory(serverQuicConfig));
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

        try (HTTP3Client http3Client = new HTTP3Client(new ClientQuicConfiguration(new SslContextFactory.Client(true), null)))
        {
            http3Client.start();

            InetSocketAddress address = new InetSocketAddress("localhost", connector.getLocalPort());
            Session.Client session = http3Client.connect(address, new Session.Client.Listener() {}).get(5, TimeUnit.SECONDS);

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
            session.newRequest(new HeadersFrame(metaData, true), new Stream.Client.Listener()
            {
                @Override
                public void onResponse(Stream.Client stream, HeadersFrame frame)
                {
                    responseRef.set((MetaData.Response)frame.getMetaData());
                    if (frame.isLast())
                        responseLatch.countDown();
                    else
                        stream.demand();
                }

                @Override
                public void onDataAvailable(Stream.Client stream)
                {
                    Stream.Data data = stream.readData();
                    if (data == null)
                    {
                        stream.demand();
                        return;
                    }
                    data.release();
                    if (data.isLast())
                        responseLatch.countDown();
                    else
                        stream.demand();
                }

                @Override
                public void onFailure(Stream.Client stream, long error, Throwable failure)
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
