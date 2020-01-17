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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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

/**
 *
 */
public class SslUploadTest
{
    private static Server server;
    private static ServerConnector connector;
    private static int total;

    @BeforeAll
    public static void startServer() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");

        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        sslContextFactory.setTrustStorePath(keystore.getAbsolutePath());
        sslContextFactory.setTrustStorePassword("storepwd");

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

        long start = System.nanoTime();
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
        // System.err.println(response);

        // long end = System.nanoTime();
        // System.out.println("upload time: " + TimeUnit.NANOSECONDS.toMillis(end - start));
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
