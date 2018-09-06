//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.proxy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class ForwardProxyServerTest
{
    @SuppressWarnings("Duplicates")
    public static Stream<Arguments> scenarios()
    {
        String keyStorePath = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();

        // no server SSL
        SslContextFactory scenario1 = null;
        // basic server SSL
        SslContextFactory scenario2 = new SslContextFactory();
        scenario2.setKeyStorePath(keyStorePath);
        scenario2.setKeyStorePassword("storepwd");
        scenario2.setKeyManagerPassword("keypwd");
        // TODO: add more SslContextFactory configurations/scenarios?

        return Stream.of(
                scenario1, scenario2
        ).map(Arguments::of);
    }

    private SslContextFactory serverSslContextFactory;
    private Server server;
    private ServerConnector serverConnector;
    private Server proxy;
    private ServerConnector proxyConnector;

    public void init(SslContextFactory scenario)
    {
        serverSslContextFactory = scenario;
    }

    protected void startServer(ConnectionFactory connectionFactory) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        serverConnector = new ServerConnector(server, serverSslContextFactory, connectionFactory);
        server.addConnector(serverConnector);
        server.start();
    }

    protected void startProxy() throws Exception
    {
        QueuedThreadPool proxyThreads = new QueuedThreadPool();
        proxyThreads.setName("proxy");
        proxy = new Server(proxyThreads);
        proxyConnector = new ServerConnector(proxy);
        proxy.addConnector(proxyConnector);
        // Under Windows, it takes a while to detect that a connection
        // attempt fails, so use an explicit timeout
        ConnectHandler connectHandler = new ConnectHandler();
        connectHandler.setConnectTimeout(1000);
        proxy.setHandler(connectHandler);

        ServletContextHandler proxyHandler = new ServletContextHandler(connectHandler, "/");
        proxyHandler.addServlet(ProxyServlet.class, "/*");

        proxy.start();
    }

    protected HttpProxy newHttpProxy()
    {
        return new HttpProxy("localhost", proxyConnector.getLocalPort());
    }

    @AfterEach
    public void stop() throws Exception
    {
        stopProxy();
        stopServer();
    }

    protected void stopServer() throws Exception
    {
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }

    protected void stopProxy() throws Exception
    {
        if (proxy != null)
        {
            proxy.stop();
            proxy.join();
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestTarget(SslContextFactory scenario) throws Exception
    {
        init(scenario);

        startServer(new AbstractConnectionFactory("http/1.1")
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return new AbstractConnection(endPoint, connector.getExecutor())
                {
                    @Override
                    public void onOpen()
                    {
                        super.onOpen();
                        fillInterested();
                    }

                    @Override
                    public void onFillable()
                    {
                        try
                        {
                            // When using TLS, multiple reads are required.
                            ByteBuffer buffer = BufferUtil.allocate(1024);
                            int filled = 0;
                            while (filled == 0)
                                filled = getEndPoint().fill(buffer);
                            Utf8StringBuilder builder = new Utf8StringBuilder();
                            builder.append(buffer);
                            String request = builder.toString();

                            // ProxyServlet will receive an absolute URI from
                            // the client, and convert it to a relative URI.
                            // The ConnectHandler won't modify what the client
                            // sent, which must be a relative URI.
                            assertThat(request.length(), Matchers.greaterThan(0));
                            if (serverSslContextFactory == null)
                                assertFalse(request.contains("http://"));
                            else
                                assertFalse(request.contains("https://"));

                            String response = "" +
                                    "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: 0\r\n" +
                                    "\r\n";
                            getEndPoint().write(Callback.NOOP, ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
                        }
                        catch (Throwable x)
                        {
                            x.printStackTrace();
                            close();
                        }
                    }
                };
            }
        });
        startProxy();

        String keyStorePath = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();
        SslContextFactory clientSsl = new SslContextFactory();
        clientSsl.setKeyStorePath(keyStorePath);
        clientSsl.setKeyStorePassword("storepwd");
        clientSsl.setKeyManagerPassword("keypwd");

        HttpClient httpClient = new HttpClient(clientSsl);
        httpClient.getProxyConfiguration().getProxies().add(newHttpProxy());
        httpClient.start();

        try
        {
            ContentResponse response = httpClient.newRequest("localhost", serverConnector.getLocalPort())
                    .scheme(serverSslContextFactory == null ? "http" : "https")
                    .method(HttpMethod.GET)
                    .path("/test")
                    .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        finally
        {
            httpClient.stop();
        }
    }
}
