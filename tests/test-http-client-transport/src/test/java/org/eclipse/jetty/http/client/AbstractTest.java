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

package org.eclipse.jetty.http.client;

import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.fcgi.client.http.HttpClientTransportOverFCGI;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class AbstractTest
{
    @Parameterized.Parameters(name = "transport: {0}")
    public static Object[] parameters() throws Exception
    {
        return Transport.values();
    }

    @Rule
    public final TestTracker tracker = new TestTracker();

    protected final HttpConfiguration httpConfig = new HttpConfiguration();
    protected final Transport transport;
    protected Server server;
    protected ServerConnector connector;
    protected ServletContextHandler context;
    protected String servletPath = "/servlet";
    protected HttpClient client;

    public AbstractTest(Transport transport)
    {
        Assume.assumeNotNull(transport);
        this.transport = transport;
    }

    public void start(Handler handler) throws Exception
    {
        assumeJavaVersionSupportsALPN();
        startServer(handler);
        startClient();
    }

    public void start(HttpServlet servlet) throws Exception
    {
        assumeJavaVersionSupportsALPN();
        startServer(servlet);
        startClient();
    }

    protected void startServer(HttpServlet servlet) throws Exception
    {
        assumeJavaVersionSupportsALPN();
        context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder, servletPath);
        startServer(context);
    }

    protected void startServer(Handler handler) throws Exception
    {
        assumeJavaVersionSupportsALPN();
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = newServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    protected SslContextFactory newServerSslContextFactory()
    {
        SslContextFactory ssl = new SslContextFactory();
        configureSslContextFactory(ssl);
        return ssl;
    }

    protected SslContextFactory newClientSslContextFactory()
    {
        SslContextFactory ssl = new SslContextFactory();
        configureSslContextFactory(ssl);
        ssl.setEndpointIdentificationAlgorithm(null);
        return ssl;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory)
    {
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
    }

    protected ServerConnector newServerConnector(Server server)
    {
        assumeJavaVersionSupportsALPN();
        return new ServerConnector(server, provideServerConnectionFactory(transport));
    }

    protected void startClient() throws Exception
    {
        assumeJavaVersionSupportsALPN();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = newHttpClient(provideClientTransport(transport), newClientSslContextFactory());
        client.setExecutor(clientThreads);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync());
        client.start();
    }

    protected ConnectionFactory[] provideServerConnectionFactory(Transport transport)
    {
        List<ConnectionFactory> result = new ArrayList<>();
        switch (transport)
        {
            case HTTP:
            {
                result.add(new HttpConnectionFactory(httpConfig));
                break;
            }
            case HTTPS:
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
                SslConnectionFactory ssl = new SslConnectionFactory(newServerSslContextFactory(), http.getProtocol());
                result.add(ssl);
                result.add(http);
                break;
            }
            case H2C:
            {
                result.add(new HTTP2CServerConnectionFactory(httpConfig));
                break;
            }
            case H2:
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfig);
                ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory("h2");
                SslConnectionFactory ssl = new SslConnectionFactory(newServerSslContextFactory(), alpn.getProtocol());
                result.add(ssl);
                result.add(alpn);
                result.add(h2);
                break;
            }
            case FCGI:
            {
                result.add(new ServerFCGIConnectionFactory(httpConfig));
                break;
            }
            default:
            {
                throw new IllegalArgumentException();
            }
        }
        return result.toArray(new ConnectionFactory[result.size()]);
    }

    protected HttpClientTransport provideClientTransport(Transport transport)
    {
        switch (transport)
        {
            case HTTP:
            case HTTPS:
            {
                return new HttpClientTransportOverHTTP(1);
            }
            case H2C:
            case H2:
            {
                HTTP2Client http2Client = newHTTP2Client();
                return new HttpClientTransportOverHTTP2(http2Client);
            }
            case FCGI:
            {
                return new HttpClientTransportOverFCGI(1, false, "");
            }
            default:
            {
                throw new IllegalArgumentException();
            }
        }
    }

    protected HttpClient newHttpClient(HttpClientTransport transport, SslContextFactory sslContextFactory)
    {
        return new HttpClient(transport, sslContextFactory);
    }

    protected HTTP2Client newHTTP2Client()
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.setSelectors(1);
        return http2Client;
    }

    protected String getScheme()
    {
        return isTransportSecure() ? "https" : "http";
    }

    protected String newURI()
    {
        return getScheme() + "://localhost:" + connector.getLocalPort();
    }

    protected boolean isTransportSecure()
    {
        switch (transport)
        {
            case HTTP:
            case H2C:
            case FCGI:
                return false;
            case HTTPS:
            case H2:
                return true;
            default:
                throw new IllegalArgumentException();
        }
    }

    @After
    public void stop() throws Exception
    {
        stopClient();
        stopServer();
    }

    protected void stopClient() throws Exception
    {
        if (client != null)
            client.stop();
    }

    protected void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    protected void assumeJavaVersionSupportsALPN()
    {
        if (transport != Transport.H2)
        {
            // Only test ALPN assumption on H2 transport
            return;
        }

        boolean isALPNSupported = false;

        if (JavaVersion.VERSION.getPlatform() >= 9)
        {
            // Java 9+ is always supported with the native java ALPN support libs
            isALPNSupported = true;
        }
        else
        {
            // Java 8 updates around update 252 are not supported in Jetty 9.3 (it requires a new ALPN support library that exists only in Java 9.4+)
            try
            {
                // JDK 8u252 has the JDK 9 ALPN API backported.
                SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
                SSLEngine.class.getMethod("getApplicationProtocol");
                // This means we have a new version of Java 8 that has ALPN backported, which Jetty 9.3 does not support.
                // Use Jetty 9.4 for proper support.
                isALPNSupported = false;
            }
            catch (NoSuchMethodException x)
            {
                // this means we have an old version of Java 8 that needs the XBootclasspath support libs
                isALPNSupported = true;
            }
        }

        Assume.assumeTrue("ALPN support exists", isALPNSupported);
    }

    protected enum Transport
    {
        HTTP, HTTPS, H2C, H2, FCGI
    }
}
