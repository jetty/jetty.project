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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.EchoHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SslContextFactoryReloadTest
{
    public static final String KEYSTORE_1 = "src/test/resources/reload_keystore_1.p12";
    public static final String KEYSTORE_2 = "src/test/resources/reload_keystore_2.p12";

    private Server server;
    private SslContextFactory.Server sslContextFactory;
    private ServerConnector connector;

    private void start(Handler handler) throws Exception
    {
        server = new Server();

        sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(KEYSTORE_1);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConfiguration httpsConfig = new HttpConfiguration();
        SecureRequestCustomizer customizer = new SecureRequestCustomizer();
        customizer.setSniHostCheck(false);
        httpsConfig.addCustomizer(customizer);
        connector = new ServerConnector(server,
            new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory(httpsConfig));
        server.addConnector(connector);

        server.setHandler(handler);

        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testReload() throws Exception
    {
        start(new TestHandler());

        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, SslContextFactory.TRUST_ALL_CERTS, null);
        SSLSocketFactory socketFactory = ctx.getSocketFactory();
        try (SSLSocket client1 = (SSLSocket)socketFactory.createSocket("localhost", connector.getLocalPort()))
        {
            String serverDN1 = client1.getSession().getPeerPrincipal().getName();
            assertThat(serverDN1, Matchers.startsWith("CN=localhost1"));

            String request =
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";

            OutputStream output1 = client1.getOutputStream();
            output1.write(request.getBytes(StandardCharsets.UTF_8));
            output1.flush();

            HttpTester.Response response1 = HttpTester.parseResponse(HttpTester.from(client1.getInputStream()));
            assertNotNull(response1);
            assertThat(response1.getStatus(), Matchers.equalTo(HttpStatus.OK_200));

            // Reconfigure SslContextFactory.
            sslContextFactory.reload(sslContextFactory ->
            {
                sslContextFactory.setKeyStorePath(KEYSTORE_2);
                sslContextFactory.setKeyStorePassword("storepwd");
            });

            // New connection should use the new keystore.
            try (SSLSocket client2 = (SSLSocket)socketFactory.createSocket("localhost", connector.getLocalPort()))
            {
                String serverDN2 = client2.getSession().getPeerPrincipal().getName();
                assertThat(serverDN2, Matchers.startsWith("CN=localhost2"));

                OutputStream output2 = client1.getOutputStream();
                output2.write(request.getBytes(StandardCharsets.UTF_8));
                output2.flush();

                HttpTester.Response response2 = HttpTester.parseResponse(HttpTester.from(client1.getInputStream()));
                assertNotNull(response2);
                assertThat(response2.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
            }

            // Must still be possible to make requests with the first connection.
            output1.write(request.getBytes(StandardCharsets.UTF_8));
            output1.flush();

            response1 = HttpTester.parseResponse(HttpTester.from(client1.getInputStream()));
            assertNotNull(response1);
            assertThat(response1.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
        }
    }

    @Test
    public void testReloadWhileServing() throws Exception
    {
        start(new TestHandler());

        Scheduler scheduler = new ScheduledExecutorScheduler();
        scheduler.start();
        try
        {
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, SslContextFactory.TRUST_ALL_CERTS, null);
            SSLSocketFactory socketFactory = ctx.getSocketFactory();

            // Perform 4 reloads while connections are being served.
            AtomicInteger reloads = new AtomicInteger(4);
            long reloadPeriod = 500;
            AtomicBoolean running = new AtomicBoolean(true);
            scheduler.schedule(new Runnable()
            {
                @Override
                public void run()
                {
                    if (reloads.decrementAndGet() == 0)
                    {
                        running.set(false);
                    }
                    else
                    {
                        try
                        {
                            sslContextFactory.reload(sslContextFactory ->
                            {
                                if (sslContextFactory.getKeyStorePath().endsWith(KEYSTORE_1))
                                    sslContextFactory.setKeyStorePath(KEYSTORE_2);
                                else
                                    sslContextFactory.setKeyStorePath(KEYSTORE_1);
                            });
                            scheduler.schedule(this, reloadPeriod, TimeUnit.MILLISECONDS);
                        }
                        catch (Exception x)
                        {
                            running.set(false);
                            reloads.set(-1);
                        }
                    }
                }
            }, reloadPeriod, TimeUnit.MILLISECONDS);

            byte[] content = new byte[16 * 1024];
            while (running.get())
            {
                try (SSLSocket client = (SSLSocket)socketFactory.createSocket("localhost", connector.getLocalPort()))
                {
                    // We need to invalidate the session every time we open a new SSLSocket.
                    // This is because when the client uses session resumption, it caches
                    // the server certificates and then checks that it is the same during
                    // a new TLS handshake. If the SslContextFactory is reloaded during the
                    // TLS handshake, the client will see the new certificate and blow up.
                    // Note that browsers can handle this case better: they will just not
                    // use session resumption and fallback to the normal TLS handshake.
                    client.getSession().invalidate();

                    String request1 =
                        "POST / HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "Content-Length: " + content.length + "\r\n" +
                            "\r\n";
                    OutputStream outputStream = client.getOutputStream();
                    outputStream.write(request1.getBytes(StandardCharsets.UTF_8));
                    outputStream.write(content);
                    outputStream.flush();

                    InputStream inputStream = client.getInputStream();
                    HttpTester.Response response1 = HttpTester.parseResponse(HttpTester.from(inputStream));
                    assertNotNull(response1);
                    assertThat(response1.getStatus(), Matchers.equalTo(HttpStatus.OK_200));

                    String request2 =
                        "GET / HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";
                    outputStream.write(request2.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    HttpTester.Response response2 = HttpTester.parseResponse(HttpTester.from(inputStream));
                    assertNotNull(response2);
                    assertThat(response2.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
                }
            }

            assertEquals(0, reloads.get());
        }
        finally
        {
            scheduler.stop();
        }
    }

    private static class TestHandler extends EchoHandler
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            if (HttpMethod.POST.is(request.getMethod()))
                return super.process(request, response, callback);

            return this::processNoContent;
        }

        public void processNoContent(Request request, Response response, Callback callback)
        {
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
            callback.succeeded();
        }
    }
}
