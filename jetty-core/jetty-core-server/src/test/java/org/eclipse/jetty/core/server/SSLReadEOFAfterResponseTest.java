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

package org.eclipse.jetty.core.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Invocable;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SSLReadEOFAfterResponseTest
{
    @Test
    public void testReadEOFAfterResponse() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore.p12");
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStoreResource(Resource.newResource(keystore));
        sslContextFactory.setKeyStorePassword("storepwd");

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, sslContextFactory);
        int idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);

        String content = "the quick brown fox jumped over the lazy dog";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        server.setHandler(new Handler.Processor(Invocable.InvocationType.BLOCKING)
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                // First: read the whole content exactly
                int length = bytes.length;
                while (length > 0)
                {
                    Content c = request.readContent();
                    if (c == null)
                    {
                        try (Blocking.Runnable blocker = Blocking.runnable())
                        {
                            request.demandContent(blocker);
                            blocker.block();
                        }
                        continue;
                    }
                    if (c.hasRemaining())
                    {
                        length -= c.remaining();
                        c.release();
                    }
                    if (c == Content.EOF)
                        callback.failed(new IllegalStateException());
                }

                // Second: write the response.
                response.setContentLength(bytes.length);
                try (Blocking.Callback blocker = Blocking.callback())
                {
                    response.write(true, blocker, BufferUtil.toBuffer(bytes));
                    blocker.block();
                }

                sleep(idleTimeout / 2);

                // Third, read the EOF.
                Content content = request.readContent();
                if (!content.isLast())
                    throw new IllegalStateException();
                callback.succeeded();
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
