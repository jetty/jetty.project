//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;

@Tag("Unstable")
@Disabled
public class SlowClientsTest
{
    private Logger logger = Log.getLogger(getClass());

    @Test
    public void testSlowClientsWithSmallThreadPool() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");

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
            server.setHandler(new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    baseRequest.setHandled(true);
                    logger.info("SERVING {}", target);
                    // Write some big content.
                    response.getOutputStream().write(new byte[contentLength]);
                    logger.info("SERVED {}", target);
                }
            });
            server.start();

            SSLContext sslContext = sslContextFactory.getSslContext();

            Assertions.assertTimeoutPreemptively(ofSeconds(10), () ->
            {
                CompletableFuture[] futures = new CompletableFuture[2 * maxThreads];
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
                            logger.info("FINISHED {}", target);
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
