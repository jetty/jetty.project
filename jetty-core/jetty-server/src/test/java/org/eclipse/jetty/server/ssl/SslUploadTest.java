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

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class SslUploadTest
{
    private Server server;
    private ServerConnector connector;
    private Path keystoreFile;
    private final String keystorePassword = "storepwd";

    @BeforeEach
    public void startServer() throws Exception
    {
        keystoreFile = MavenPaths.findTestResourceFile("keystore.p12");

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystoreFile.toString());
        sslContextFactory.setKeyStorePassword(keystorePassword);

        server = new Server();
        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        server.setHandler(new EmptyHandler());

        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testUpload() throws Exception
    {
        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        SslContextFactory ctx = connector.getConnectionFactory(SslConnectionFactory.class).getSslContextFactory();
        try (InputStream stream = Files.newInputStream(keystoreFile))
        {
            keystore.load(stream, keystorePassword.toCharArray());
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keystore);
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

        try (SSLSocket socket = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", connector.getLocalPort()))
        {
            byte[] requestBody = new byte[16777216];
            Arrays.fill(requestBody, (byte)'x');

            String rawRequest = """
                POST / HTTP/1.1\r
                Host: localhost\r
                Content-Length: %d\r
                Content-Type: bytes\r
                Connection: close\r
                \r
                """.formatted(requestBody.length);

            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream())
            {
                out.write(rawRequest.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write(requestBody);
                out.flush();

                String rawResponse = IO.toString(in, StandardCharsets.UTF_8);

                HttpTester.Response response = HttpTester.parseResponse(rawResponse);
                assertThat(response.getStatus(), is(200));

                String responseBody = response.getContent();
                assertThat(responseBody, containsString("Read %d".formatted(requestBody.length)));
            }
        }
    }

    private static class EmptyHandler extends Handler.Abstract
    {
        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            ByteBuffer input = Content.Source.asByteBuffer(request);
            response.write(true, BufferUtil.toBuffer(("Read " + input.remaining()).getBytes()), callback);
            return true;
        }
    }
}
