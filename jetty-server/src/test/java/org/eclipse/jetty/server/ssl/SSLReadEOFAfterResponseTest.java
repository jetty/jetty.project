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
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

// Only in JDK 11 is possible to use SSLSocket.shutdownOutput().
@DisabledOnJre({JRE.JAVA_8, JRE.JAVA_9, JRE.JAVA_10})
public class SSLReadEOFAfterResponseTest
{
    @Test
    public void testReadEOFAfterResponse() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStoreResource(Resource.newResource(keystore));
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, sslContextFactory);
        int idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);

        String content = "the quick brown fox jumped over the lazy dog";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                // First: read the whole content.
                InputStream input = request.getInputStream();
                int length = bytes.length;
                while (length > 0)
                {
                    int read = input.read();
                    if (read < 0)
                        throw new IllegalStateException();
                    --length;
                }

                // Second: write the response.
                response.setContentLength(bytes.length);
                response.getOutputStream().write(bytes);
                response.flushBuffer();

                sleep(idleTimeout / 2);

                // Third, read the EOF.
                int read = input.read();
                if (read >= 0)
                    throw new IllegalStateException();
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
