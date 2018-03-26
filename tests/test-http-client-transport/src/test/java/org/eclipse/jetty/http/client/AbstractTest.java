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

package org.eclipse.jetty.http.client;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

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
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
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
import org.eclipse.jetty.unixsocket.UnixSocketConnector;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class AbstractTest
{
    @Parameterized.Parameters(name = "transport: {0}")
    public static Object[] parameters() throws Exception
    {
        String transports = System.getProperty("org.eclipse.jetty.http.client.AbstractTest.Transports");

        if (!StringUtil.isBlank(transports))
            return Arrays.stream(transports.split("\\s*,\\s*"))
                .map(Transport::valueOf)
                .collect(Collectors.toList()).toArray();
        
        // TODO #2014 too many test failures, don't test unix socket client for now.
        // if (OS.IS_UNIX)
        //     return Transport.values();
        
        return EnumSet.complementOf(EnumSet.of(Transport.UNIX_SOCKET)).toArray();
    }


    @Rule
    public final TestTracker tracker = new TestTracker();

    protected final HttpConfiguration httpConfig = new HttpConfiguration();
    protected final Transport transport;
    protected SslContextFactory sslContextFactory;
    protected Server server;
    protected Connector connector;
    protected ServletContextHandler context;
    protected String servletPath = "/servlet";
    protected HttpClient client;
    protected Path sockFile;

    public AbstractTest(Transport transport)
    {
        Assume.assumeNotNull(transport);
        this.transport = transport;
    }

    public void start(Handler handler) throws Exception
    {
        startServer(handler);
        startClient();
    }

    public void start(HttpServlet servlet) throws Exception
    {
        startServer(servlet);
        startClient();
    }

    @Before
    public void before() throws Exception
    {
        if(sockFile == null || !Files.exists( sockFile ))
        {
            sockFile = Files.createTempFile("unix", ".sock" );
            Files.delete( sockFile );
        }
    }

    @After
    public void stop() throws Exception
    {
        stopClient();
        stopServer();
        if (sockFile!=null)
        {
            Files.deleteIfExists( sockFile );
        }
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
    
    protected void startServer(HttpServlet servlet) throws Exception
    {
        context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder, servletPath);
        startServer(context);
    }

    protected void startServer(Handler handler) throws Exception
    {
        sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        serverThreads.setDetailedDump(true);
        server = new Server(serverThreads);
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);
        connector = newServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        try
        {
            server.start();
        }
        catch ( Exception e )
        {
            e.printStackTrace();
        }
    }

    protected Connector newServerConnector(Server server) throws Exception
    {
        if (transport == Transport.UNIX_SOCKET)
        {
            UnixSocketConnector unixSocketConnector = new UnixSocketConnector(server, provideServerConnectionFactory( transport ));
            unixSocketConnector.setUnixSocket( sockFile.toString() );
            return unixSocketConnector;
        }
        return new ServerConnector(server, provideServerConnectionFactory(transport));
    }

    protected void startClient() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientThreads.setDetailedDump(true);
        client = newHttpClient(provideClientTransport(transport), sslContextFactory);
        client.setExecutor(clientThreads);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync());
        client.start();
        if (server != null)
            server.addBean(client);
    }

    protected ConnectionFactory[] provideServerConnectionFactory(Transport transport)
    {
        List<ConnectionFactory> result = new ArrayList<>();
        switch (transport)
        {
            case UNIX_SOCKET:
            case HTTP:
            {
                result.add(new HttpConnectionFactory(httpConfig));
                break;
            }
            case HTTPS:
            {
                httpConfig.addCustomizer(new SecureRequestCustomizer());
                HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
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
                SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
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
            case UNIX_SOCKET:
            {
                return new HttpClientTransportOverUnixSockets( sockFile.toString() );
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
        if (connector instanceof  ServerConnector)
            return getScheme() + "://localhost:" + ServerConnector.class.cast(connector).getLocalPort();
        return getScheme() + "://localhost";
    }

    protected boolean isTransportSecure()
    {
        switch (transport)
        {
            case UNIX_SOCKET:
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

    protected enum Transport
    {
        HTTP, HTTPS, H2C, H2, FCGI, UNIX_SOCKET;
    }
}
