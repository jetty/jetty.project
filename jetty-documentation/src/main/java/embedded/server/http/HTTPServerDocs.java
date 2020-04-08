//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package embedded.server.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

@SuppressWarnings("unused")
public class HTTPServerDocs
{
    public void simple() throws Exception
    {
        // tag::simple[]
        // Create and configure a ThreadPool.
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("server");

        // Create a Server instance.
        Server server = new Server(threadPool);

        // Create a ServerConnector to accept connections from clients.
        Connector connector = new ServerConnector(server);

        // Add the Connector to the Server
        server.addConnector(connector);

        // Set a simple Handler to handle requests/responses.
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                // Mark the request as handled so that it
                // will not be processed by other handlers.
                jettyRequest.setHandled(true);
            }
        });

        // Start the Server so it starts accepting connections from clients.
        server.start();
        // end::simple[]
    }

    public void configureConnector() throws Exception
    {
        // tag::configureConnector[]
        Server server = new Server();

        // The number of acceptor threads.
        int acceptors = 1;

        // The number of selectors.
        int selectors = 1;

        // Create a ServerConnector instance.
        ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());

        // Configure TCP parameters.

        // The TCP port to listen to.
        connector.setPort(8080);
        // The TCP address to bind to.
        connector.setHost("127.0.0.1");
        // The TCP accept queue size.
        connector.setAcceptQueueSize(128);

        server.addConnector(connector);
        server.start();
        // end::configureConnector[]
    }

    public void configureConnectors() throws Exception
    {
        // tag::configureConnectors[]
        Server server = new Server();

        // Create a ServerConnector instance on port 8080.
        ServerConnector connector1 = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
        connector1.setPort(8080);
        server.addConnector(connector1);

        // Create another ServerConnector instance on port 9090,
        // for example with a different HTTP configuration.
        HttpConfiguration httpConfig2 = new HttpConfiguration();
        httpConfig2.setHttpCompliance(HttpCompliance.LEGACY);
        ServerConnector connector2 = new ServerConnector(server, 1, 1, new HttpConnectionFactory(httpConfig2));
        connector2.setPort(9090);
        server.addConnector(connector2);

        server.start();
        // end::configureConnectors[]
    }

    public void http11() throws Exception
    {
        // tag::http11[]
        Server server = new Server();

        // The HTTP configuration object.
        HttpConfiguration httpConfig = new HttpConfiguration();
        // Configure the HTTP support, for example:
        httpConfig.setSendServerVersion(false);

        // The ConnectionFactory for HTTP/1.1.
        HttpConnectionFactory http11 = new HttpConnectionFactory(httpConfig);

        // Create the ServerConnector.
        ServerConnector connector = new ServerConnector(server, http11);
        connector.setPort(8080);

        server.addConnector(connector);
        server.start();
        // end::http11[]
    }

    public void proxyHTTP() throws Exception
    {
        // tag::proxyHTTP[]
        Server server = new Server();

        // The HTTP configuration object.
        HttpConfiguration httpConfig = new HttpConfiguration();
        // Configure the HTTP support, for example:
        httpConfig.setSendServerVersion(false);

        // The ConnectionFactory for HTTP/1.1.
        HttpConnectionFactory http11 = new HttpConnectionFactory(httpConfig);

        // The ConnectionFactory for the PROXY protocol.
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http11.getProtocol());

        // Create the ServerConnector.
        ServerConnector connector = new ServerConnector(server, proxy, http11);
        connector.setPort(8080);

        server.addConnector(connector);
        server.start();
        // end::proxyHTTP[]
    }

    public void tlsHttp11() throws Exception
    {
        // tag::tlsHttp11[]
        Server server = new Server();

        // The HTTP configuration object.
        HttpConfiguration httpConfig = new HttpConfiguration();
        // Add the SecureRequestCustomizer because we are using TLS.
        httpConfig.addCustomizer(new SecureRequestCustomizer());

        // The ConnectionFactory for HTTP/1.1.
        HttpConnectionFactory http11 = new HttpConnectionFactory(httpConfig);

        // Configure the SslContextFactory with the keyStore information.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");

        // The ConnectionFactory for TLS.
        SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, http11.getProtocol());

        // The ServerConnector instance.
        ServerConnector connector = new ServerConnector(server, tls, http11);
        connector.setPort(8443);

        server.addConnector(connector);
        server.start();
        // end::tlsHttp11[]
    }

    public void http11H2C() throws Exception
    {
        // tag::http11H2C[]
        Server server = new Server();

        // The HTTP configuration object.
        HttpConfiguration httpConfig = new HttpConfiguration();

        // The ConnectionFactory for HTTP/1.1.
        HttpConnectionFactory http11 = new HttpConnectionFactory(httpConfig);

        // The ConnectionFactory for clear-text HTTP/2.
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);

        // The ServerConnector instance.
        ServerConnector connector = new ServerConnector(server, http11, h2c);
        connector.setPort(8080);

        server.addConnector(connector);
        server.start();
        // end::http11H2C[]
    }

    public void tlsALPNHTTP() throws Exception
    {
        // tag::tlsALPNHTTP[]
        Server server = new Server();

        // The HTTP configuration object.
        HttpConfiguration httpConfig = new HttpConfiguration();
        // Add the SecureRequestCustomizer because we are using TLS.
        httpConfig.addCustomizer(new SecureRequestCustomizer());

        // The ConnectionFactory for HTTP/1.1.
        HttpConnectionFactory http11 = new HttpConnectionFactory(httpConfig);

        // The ConnectionFactory for HTTP/2.
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfig);

        // The ALPN ConnectionFactory.
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        // The default protocol to use in case there is no negotiation.
        alpn.setDefaultProtocol(http11.getProtocol());

        // Configure the SslContextFactory with the keyStore information.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");

        // The ConnectionFactory for TLS.
        SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());

        // The ServerConnector instance.
        ServerConnector connector = new ServerConnector(server, tls, alpn, http11, h2);
        connector.setPort(8443);

        server.addConnector(connector);
        server.start();
        // end::tlsALPNHTTP[]
    }

    public void tree() throws Exception
    {
        class LoggingHandler extends AbstractHandler
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
            }
        }

        class App1Handler extends AbstractHandler
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
            }
        }

        class App2Handler extends HandlerWrapper
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
            }
        }

        // tag::tree[]
        // Create a Server instance.
        Server server = new Server();

        HandlerCollection collection = new HandlerCollection();
        // Link the root Handler with the Server.
        server.setHandler(collection);

        HandlerList list = new HandlerList();
        collection.addHandler(list);
        collection.addHandler(new LoggingHandler());

        list.addHandler(new App1Handler());
        App2Handler app2Handler = new App2Handler();
        list.addHandler(app2Handler);

        app2Handler.setHandler(new ServletHandler());
        // end::tree[]
    }
}
