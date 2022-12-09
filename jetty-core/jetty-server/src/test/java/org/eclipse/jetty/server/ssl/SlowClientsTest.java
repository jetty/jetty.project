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

package org.eclipse.jetty.server.ssl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.time.Duration.ofSeconds;

@Tag("Unstable")
@Disabled
public class SlowClientsTest
{
    private static final Logger LOG = LoggerFactory.getLogger(SlowClientsTest.class);

    @Test
    public void testSlowClientsWithSmallThreadPool() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore.p12");
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");

        int maxThreads = 6;
        int contentLength = 8 * 1024 * 1024;
        QueuedThreadPool serverThreads = new QueuedThreadPool(maxThreads);
        serverThreads.setDetailedDump(true);
        Server server = new Server(serverThreads);

        try
        {
            ServerConnector connector = new ServerConnector(server, 1, 1, sslContextFactory);
            connector.setPort(8888);
            server.addConnector(connector);
            server.setHandler(new Handler.Abstract()
            {
                @Override
                public void process(Request request, Response response, Callback callback)
                {
                    LOG.info("SERVING {}", request);
                    // Write some big content.
                    response.write(true, BufferUtil.toBuffer(new byte[contentLength]), new Callback()
                        {
                            @Override
                            public void succeeded()
                            {
                                callback.succeeded();
                                LOG.info("SERVED {}", request);
                            }

                            @Override
                            public void failed(Throwable x)
                            {
                                callback.failed(x);
                            }
                        }
                    );
                }
            });
            server.start();

            SSLContext sslContext = sslContextFactory.getSslContext();

            Assertions.assertTimeoutPreemptively(ofSeconds(10), () ->
            {
                CompletableFuture<?>[] futures = new CompletableFuture[2 * maxThreads];
                ExecutorService executor = Executors.newFixedThreadPool(futures.length);
                for (int i = 0; i < futures.length; i++)
                {
                    int k = i;
                    futures[i] = CompletableFuture.runAsync(() ->
                    {
                        try (SSLSocket socket = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", connector.getLocalPort()))
                        {
                            socket.setSoTimeout(contentLength / 1024);
                            OutputStream output = socket.getOutputStream();
                            String target = "/" + k;
                            String request = "GET " + target + " HTTP/1.1\r\n" +
                                "Host: localhost\r\n" +
                                "Connection: close\r\n" +
                                "\r\n";
                            output.write(request.getBytes(StandardCharsets.UTF_8));
                            output.flush();

                            while (serverThreads.getIdleThreads() > 0)
                            {
                                Thread.sleep(50);
                            }

                            InputStream input = socket.getInputStream();
                            while (true)
                            {
                                int read = input.read();
                                if (read < 0)
                                    break;
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
        finally
        {
            server.stop();
        }
    }
}
