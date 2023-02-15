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

package org.eclipse.jetty.server.ssl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.time.Duration.ofSeconds;

public class SlowClientsTest
{
    private static final Logger LOG = LoggerFactory.getLogger(SlowClientsTest.class);
    private Server server;
    private SslContextFactory.Server sslContextFactory;

    @BeforeEach
    public void initServer() throws Exception
    {
        Path keystoreFile = MavenPaths.findTestResourceFile("keystore.p12");
        sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystoreFile.toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1, sslContextFactory);
        connector.setPort(0);
        server.addConnector(connector);
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    public void startServer(Handler handler) throws Exception
    {
        server.setHandler(handler);
        server.start();
    }

    public Socket newSocketToServer() throws IOException
    {
        URI serverURI = server.getURI();
        SSLContext sslContext = sslContextFactory.getSslContext();
        return sslContext.getSocketFactory().createSocket(serverURI.getHost(), serverURI.getPort());
    }

    @Test
    public void testSlowClientsWithSmallThreadPool() throws Exception
    {
        final int maxThreads = 6;
        final int contentLength = 8 * 1024 * 1024; // 8MB

        ((QueuedThreadPool)server.getThreadPool()).setMaxThreads(maxThreads);
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                response.write(true, BufferUtil.toBuffer(contentLength), callback);
                return true;
            }
        });

        Assertions.assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            // Twice as many clients as threads in thread pool.
            CompletableFuture<?>[] futures = new CompletableFuture[2 * maxThreads];
            ExecutorService executor = Executors.newFixedThreadPool(futures.length);
            for (int i = 0; i < futures.length; i++)
            {
                int k = i;
                futures[i] = CompletableFuture.runAsync(() ->
                {
                    try (Socket socket = newSocketToServer())
                    {
                        socket.setSoTimeout(contentLength / 1024);
                        OutputStream output = socket.getOutputStream();
                        String target = "/" + k;
                        String rawRequest = """
                            GET %s HTTP/1.1\r
                            Host: localhost\r
                            Connection: close\r
                            \r
                            """.formatted(target);
                        output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
                        output.flush();

                        int delayReadCount = 10;

                        InputStream input = socket.getInputStream();
                        while (true)
                        {
                            int read = input.read();
                            if (read < 0)
                                break;
                            // simulate a slow client (for a bit).
                            // we are testing that the server thread pool doesn't misbehave
                            // in this scenario, where there are more clients active than server threads.
                            if (delayReadCount > 0)
                            {
                                TimeUnit.MILLISECONDS.sleep(200);
                                delayReadCount--;
                            }
                        }
                        LOG.info("FINISHED {}", target);
                    }
                    catch (IOException x)
                    {
                        throw new UncheckedIOException(x);
                    }
                    catch (InterruptedException x)
                    {
                        throw new UncheckedIOException(new InterruptedIOException());
                    }
                }, executor);
            }
            CompletableFuture.allOf(futures).join();
        });
    }
}
