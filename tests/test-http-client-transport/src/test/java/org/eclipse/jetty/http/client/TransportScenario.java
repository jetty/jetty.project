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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.AbstractConnector;
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
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.unixsocket.UnixSocketConnector;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class TransportScenario
{
    private static final Logger LOG = Log.getLogger(TransportScenario.class);

    protected final HttpConfiguration httpConfig = new HttpConfiguration();
    protected final Transport transport;
    protected SslContextFactory sslContextFactory;
    protected Server server;
    protected Connector connector;
    protected ServletContextHandler context;
    protected String servletPath = "/servlet";
    protected HttpClient client;
    protected Path sockFile;

    public TransportScenario(final Transport transport) throws IOException
    {
        this.transport = transport;

        if(sockFile == null || !Files.exists( sockFile ))
        {
            Path target = MavenTestingUtils.getTargetPath();
            sockFile = Files.createTempFile(target,"unix", ".sock" );
            Files.delete( sockFile );
        }
    }

    public Optional<String> getNetworkConnectorLocalPort()
    {
        if (connector instanceof ServerConnector)
        {
            ServerConnector serverConnector = (ServerConnector) connector;
            return Optional.of(Integer.toString(serverConnector.getLocalPort()));
        }

        return Optional.empty();
    }

    public Optional<Integer> getNetworkConnectorLocalPortInt()
    {
        if (connector instanceof ServerConnector)
        {
            ServerConnector serverConnector = (ServerConnector) connector;
            return Optional.of(serverConnector.getLocalPort());
        }

        return Optional.empty();
    }

    public String getScheme()
    {
        return isTransportSecure() ? "https" : "http";
    }

    @Deprecated
    public boolean isHttp1Based()
    {
        return transport.isHttp1Based();
    }

    @Deprecated
    public boolean isTransportSecure()
    {
        return transport.isTlsBased();
    }

    @Deprecated
    public boolean isHttp2Based()
    {
        return transport.isHttp2Based();
    }

    public HTTP2Client newHTTP2Client()
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.setSelectors(1);
        return http2Client;
    }

    public HttpClient newHttpClient(HttpClientTransport transport, SslContextFactory sslContextFactory)
    {
        return new HttpClient(transport, sslContextFactory);
    }

    public Connector newServerConnector(Server server) throws Exception
    {
        if (transport == Transport.UNIX_SOCKET)
        {
            UnixSocketConnector unixSocketConnector = new UnixSocketConnector(server, provideServerConnectionFactory( transport ));
            unixSocketConnector.setUnixSocket( sockFile.toString() );
            return unixSocketConnector;
        }
        return new ServerConnector(server, provideServerConnectionFactory(transport));
    }

    public String newURI()
    {
        StringBuilder ret = new StringBuilder();
        ret.append(getScheme());
        ret.append("://localhost");
        Optional<String> localPort = getNetworkConnectorLocalPort();
        if (localPort.isPresent())
        {
            ret.append(':').append(localPort.get());
        }
        return ret.toString();
    }

    public HttpClientTransport provideClientTransport()
    {
        return provideClientTransport(this.transport);
    }

    public HttpClientTransport provideClientTransport(Transport transport)
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

    public ConnectionFactory[] provideServerConnectionFactory(Transport transport)
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

    public void setConnectionIdleTimeout(long idleTimeout)
    {
        if (connector instanceof AbstractConnector)
            AbstractConnector.class.cast(connector).setIdleTimeout(idleTimeout);
    }

    public void setServerIdleTimeout(long idleTimeout)
    {
        AbstractHTTP2ServerConnectionFactory h2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        if (h2 != null)
            h2.setStreamIdleTimeout(idleTimeout);
        else
            setConnectionIdleTimeout(idleTimeout);
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

    public void startClient() throws Exception
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

    public void startServer(HttpServlet servlet) throws Exception
    {
        context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder, servletPath);
        startServer(context);
    }

    public void startServer(Handler handler) throws Exception
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

    public void stopClient() throws Exception
    {
        if (client != null)
            client.stop();
    }

    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    public void stop()
    {
        try
        {
            stopClient();
        }
        catch (Exception ignore)
        {
            LOG.ignore(ignore);
        }

        try
        {
            stopServer();
        }
        catch (Exception ignore)
        {
            LOG.ignore(ignore);
        }

        if (sockFile!=null)
        {
            try
            {
                Files.deleteIfExists( sockFile );
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
        }
    }
}
