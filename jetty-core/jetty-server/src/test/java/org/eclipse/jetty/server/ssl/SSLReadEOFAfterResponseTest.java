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
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandlerTest;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SSLReadEOFAfterResponseTest
{
    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testReadEOFAfterResponse() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore.p12");
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStoreResource(ResourceFactory.root().newResource(keystore.toPath()));
        sslContextFactory.setKeyStorePassword("storepwd");

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, sslContextFactory);
        int idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);

        String content = "the quick brown fox jumped over the lazy dog";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        server.setHandler(new GzipHandlerTest.DumpHandler.Blocking()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                // First: read the whole content exactly
                int length = bytes.length;
                while (length > 0)
                {
                    Content.Chunk c = request.read();
                    if (c == null)
                    {
                        try (Blocker.Runnable blocker = Blocker.runnable())
                        {
                            request.demand(blocker);
                            blocker.block();
                        }
                        continue;
                    }
                    if (c.hasRemaining())
                    {
                        length -= c.remaining();
                        c.release();
                    }
                    if (c == Content.Chunk.EOF)
                        callback.failed(new IllegalStateException());
                }

                // Second: write the response.
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, bytes.length);
                try (Blocker.Callback blocker = Blocker.callback())
                {
                    response.write(true, BufferUtil.toBuffer(bytes), blocker);
                    blocker.block();
                }

                sleep(idleTimeout / 2);

                // Third, read the EOF.
                Content.Chunk chunk = request.read();
                if (!chunk.isLast())
                    throw new IllegalStateException();
                callback.succeeded();
                return true;
            }
        });
        server.start();

        try
        {
            SSLContext sslContext = sslContextFactory.getSslContext();
            try (Socket client = sslContext.getSocketFactory().createSocket("localhost", connector.getLocalPort()))
            {
                client.setSoTimeout(5 * idleTimeout);

                OutputStream output = client.getOutputStream();
                String request =
                    "POST / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + content.length() + "\r\n" +
                        "\r\n";
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.write(bytes);
                output.flush();

                // Read the response.
                InputStream input = client.getInputStream();
                int crlfs = 0;
                while (true)
                {
                    int read = input.read();
                    assertThat(read, Matchers.greaterThanOrEqualTo(0));
                    if (read == '\r' || read == '\n')
                        ++crlfs;
                    else
                        crlfs = 0;
                    if (crlfs == 4)
                        break;
                }
                for (byte b : bytes)
                {
                    assertEquals(b, input.read());
                }

                // Shutdown the output so the server reads the TLS close_notify.
                client.shutdownOutput();
                // client.close();

                // The connection should now be idle timed out by the server.
                int read = input.read();
                assertEquals(-1, read);
            }
        }
        finally
        {
            server.stop();
        }
    }

    private void sleep(long time) throws IOException
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
    }
}
