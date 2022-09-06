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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SslUploadTest
{
    private static Server server;
    private static ServerConnector connector;
    private static int total;

    @BeforeAll
    public static void startServer() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore.p12");

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");

        server = new Server();
        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        server.setHandler(new EmptyHandler());

        server.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    @Disabled
    public void test() throws Exception
    {
        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        SslContextFactory ctx = connector.getConnectionFactory(SslConnectionFactory.class).getSslContextFactory();
        try (InputStream stream = new FileInputStream(ctx.getKeyStorePath()))
        {
            keystore.load(stream, "storepwd".toCharArray());
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keystore);
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

        final SSLSocket socket = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", connector.getLocalPort());

        // Simulate async close
        /*
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    sleep(100);
                    socket.close();
                }
                catch (IOException x)
                {
                    x.printStackTrace();
                }
                catch (InterruptedException x)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }.start();
        */

        OutputStream out = socket.getOutputStream();
        out.write("POST / HTTP/1.1\r\n".getBytes());
        out.write("Host: localhost\r\n".getBytes());
        out.write("Content-Length: 16777216\r\n".getBytes());
        out.write("Content-Type: bytes\r\n".getBytes());
        out.write("Connection: close\r\n".getBytes());
        out.write("\r\n".getBytes());
        out.flush();

        byte[] requestContent = new byte[16777216];
        Arrays.fill(requestContent, (byte)120);
        out.write(requestContent);
        out.flush();

        InputStream in = socket.getInputStream();
        String response = IO.toString(in);
        assertTrue(response.indexOf("200") > 0);

        assertEquals(requestContent.length, total);
    }

    private static class EmptyHandler extends AbstractHandler
    {
        @Override
        public void handle(String path, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);
            InputStream in = request.getInputStream();
            byte[] b = new byte[4096 * 4];
            int read;
            while ((read = in.read(b)) >= 0)
            {
                total += read;
            }
            System.err.println("Read " + total);
        }
    }
}
