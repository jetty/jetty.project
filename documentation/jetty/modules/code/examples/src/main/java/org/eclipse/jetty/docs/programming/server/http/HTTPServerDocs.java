//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.server.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.Security;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.ResourceServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPartConfig;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.rewrite.handler.CompactPathRule;
import org.eclipse.jetty.rewrite.handler.RedirectRegexRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.rewrite.handler.RewriteRegexRule;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.ConnectionLimit;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.MemoryConnector;
import org.eclipse.jetty.server.MemoryTransport;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLogWriter;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.Slf4jRequestLogWriter;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.CrossOriginHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.EventsHandler;
import org.eclipse.jetty.server.handler.GracefulHandler;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.SecuredRedirectHandler;
import org.eclipse.jetty.server.handler.StateTrackingHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.KeyStoreScanner;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.nio.charset.StandardCharsets.UTF_8;

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
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Succeed the callback to signal that the
                // request/response processing is complete.
                callback.succeeded();
                return true;
            }
        });

        // Start the Server to start accepting connections from clients.
        server.start();
        // end::simple[]
    }

    public void serverRequestLogSLF4J()
    {
        // tag::serverRequestLogSLF4J[]
        Server server = new Server();

        // Sets the RequestLog to log to an SLF4J logger named "org.eclipse.jetty.server.RequestLog" at INFO level.
        server.setRequestLog(new CustomRequestLog(new Slf4jRequestLogWriter(), CustomRequestLog.EXTENDED_NCSA_FORMAT));
        // end::serverRequestLogSLF4J[]
    }

    public void serverRequestLogFile()
    {
        // tag::serverRequestLogFile[]
        Server server = new Server();

        // Use a file name with the pattern 'yyyy_MM_dd' so rolled over files retain their date.
        RequestLogWriter logWriter = new RequestLogWriter("/var/log/yyyy_MM_dd.jetty.request.log");
        // Retain rolled over files for 2 weeks.
        logWriter.setRetainDays(14);
        // Log times are in the current time zone.
        logWriter.setTimeZone(TimeZone.getDefault().getID());

        // Set the RequestLog to log to the given file, rolling over at midnight.
        server.setRequestLog(new CustomRequestLog(logWriter, CustomRequestLog.EXTENDED_NCSA_FORMAT));
        // end::serverRequestLogFile[]
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
        ServerConnector connector = new ServerConnector(server, acceptors, selectors, new HttpConnectionFactory());

        // Configure TCP/IP parameters.

        // The port to listen to.
        connector.setPort(8080);
        // The address to bind to.
        connector.setHost("127.0.0.1");

        // The TCP accept queue size.
        connector.setAcceptQueueSize(128);

        server.addConnector(connector);
        server.start();
        // end::configureConnector[]
    }

    public void configureConnectorUnix() throws Exception
    {
        // tag::configureConnectorUnix[]
        Server server = new Server();

        // The number of acceptor threads.
        int acceptors = 1;

        // The number of selectors.
        int selectors = 1;

        // Create a ServerConnector instance.
        UnixDomainServerConnector connector = new UnixDomainServerConnector(server, acceptors, selectors, new HttpConnectionFactory());

        // Configure Unix-Domain parameters.

        // The Unix-Domain path to listen to.
        connector.setUnixDomainPath(Path.of("/tmp/jetty.sock"));

        // The TCP accept queue size.
        connector.setAcceptQueueSize(128);

        server.addConnector(connector);
        server.start();
        // end::configureConnectorUnix[]
    }

    public void configureConnectorQuic() throws Exception
    {
        // tag::configureConnectorQuic[]
        Server server = new Server();

        // Configure the SslContextFactory with the keyStore information.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");

        // Create a QuicServerConnector instance.
        Path pemWorkDir = Path.of("/path/to/pem/dir");
        ServerQuicConfiguration serverQuicConfig = new ServerQuicConfiguration(sslContextFactory, pemWorkDir);
        QuicServerConnector connector = new QuicServerConnector(server, serverQuicConfig, new HTTP3ServerConnectionFactory(serverQuicConfig));

        // The port to listen to.
        connector.setPort(8080);
        // The address to bind to.
        connector.setHost("127.0.0.1");

        server.addConnector(connector);
        server.start();
        // end::configureConnectorQuic[]
    }

    public void memoryConnector() throws Exception
    {
        // tag::memoryConnector[]
        Server server = new Server();

        // Create a MemoryConnector instance that speaks HTTP/1.1.
        MemoryConnector connector = new MemoryConnector(server, new HttpConnectionFactory());

        server.addConnector(connector);
        server.start();

        // The code above is the server-side.
        // ----
        // The code below is the client-side.

        HttpClient httpClient = new HttpClient();
        httpClient.start();

        ContentResponse response = httpClient.newRequest("http://localhost/")
            // Use the memory Transport to communicate with the server-side.
            .transport(new MemoryTransport(connector))
            .send();
        // end::memoryConnector[]
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

    public void sameRandomPort() throws Exception
    {
        // tag::sameRandomPort[]
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");

        Server server = new Server();

        // The plain HTTP configuration.
        HttpConfiguration plainConfig = new HttpConfiguration();

        // The secure HTTP configuration.
        HttpConfiguration secureConfig = new HttpConfiguration(plainConfig);
        secureConfig.addCustomizer(new SecureRequestCustomizer());

        // First, create the secure connector for HTTPS and HTTP/2.
        HttpConnectionFactory https = new HttpConnectionFactory(secureConfig);
        HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(secureConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(https.getProtocol());
        ConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, https.getProtocol());
        ServerConnector secureConnector = new ServerConnector(server, 1, 1, ssl, alpn, http2, https);
        server.addConnector(secureConnector);

        // Second, create the plain connector for HTTP.
        HttpConnectionFactory http = new HttpConnectionFactory(plainConfig);
        ServerConnector plainConnector = new ServerConnector(server, 1, 1, http);
        server.addConnector(plainConnector);

        // Third, create the connector for HTTP/3.
        Path pemWorkDir = Path.of("/path/to/pem/dir");
        ServerQuicConfiguration serverQuicConfig = new ServerQuicConfiguration(sslContextFactory, pemWorkDir);
        QuicServerConnector http3Connector = new QuicServerConnector(server, serverQuicConfig, new HTTP3ServerConnectionFactory(serverQuicConfig));
        server.addConnector(http3Connector);

        // Set up a listener so that when the secure connector starts,
        // it configures the other connectors that have not started yet.
        secureConnector.addEventListener(new NetworkConnector.Listener()
        {
            @Override
            public void onOpen(NetworkConnector connector)
            {
                int port = connector.getLocalPort();

                // Configure the plain connector for secure redirects from http to https.
                plainConfig.setSecurePort(port);

                // Configure the HTTP3 connector port to be the same as HTTPS/HTTP2.
                http3Connector.setPort(port);
            }
        });

        server.start();
        // end::sameRandomPort[]
    }

    public void connectionLimit()
    {
        // tag::connectionLimit[]
        Server server = new Server();

        // Limit connections to the server, across all connectors.
        ConnectionLimit serverConnectionLimit = new ConnectionLimit(1024, server);
        server.addBean(serverConnectionLimit);

        ServerConnector connector1 = new ServerConnector(server);
        connector1.setPort(8080);
        server.addConnector(connector1);

        ServerConnector connector2 = new ServerConnector(server);
        connector2.setPort(9090);
        server.addConnector(connector2);
        // Limit connections for this connector only.
        ConnectionLimit connectorConnectionLimit = new ConnectionLimit(64, connector2);
        connector2.addBean(connectorConnectionLimit);
        // end::connectionLimit[]
    }

    public void sslHandshakeListener() throws Exception
    {
        // tag::sslHandshakeListener[]
        // Create a SslHandshakeListener.
        SslHandshakeListener listener = new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event) throws SSLException
            {
                SSLEngine sslEngine = event.getSSLEngine();
                System.getLogger("tls").log(INFO, "TLS handshake successful to %s", sslEngine.getPeerHost());
            }

            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                SSLEngine sslEngine = event.getSSLEngine();
                System.getLogger("tls").log(ERROR, "TLS handshake failure to %s", sslEngine.getPeerHost(), failure);
            }
        };

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Add the SslHandshakeListener as bean to ServerConnector.
        // The listener will be notified of TLS handshakes success and failure.
        connector.addBean(listener);
        // end::sslHandshakeListener[]
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

    public void proxyHTTPUnix() throws Exception
    {
        // tag::proxyHTTPUnix[]
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
        UnixDomainServerConnector connector = new UnixDomainServerConnector(server, proxy, http11);
        connector.setUnixDomainPath(Path.of("/tmp/jetty.sock"));

        server.addConnector(connector);
        server.start();
        // end::proxyHTTPUnix[]
    }

    public void tlsHttp11() throws Exception
    {
        // tag::tlsHttp11[]
        Server server = new Server();

        // The HTTP configuration object.
        HttpConfiguration httpConfig = new HttpConfiguration();
        // Add the SecureRequestCustomizer because TLS is used.
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
        // Add the SecureRequestCustomizer because TLS is used.
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
        ServerConnector connector = new ServerConnector(server, tls, alpn, h2, http11);
        connector.setPort(8443);

        server.addConnector(connector);
        server.start();
        // end::tlsALPNHTTP[]
    }

    public void h3() throws Exception
    {
        // tag::h3[]
        Server server = new Server();

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());

        // Create and configure the HTTP/3 connector.
        // It is mandatory to configure the PEM directory.
        Path pemWorkDir = Path.of("/path/to/pem/dir");
        ServerQuicConfiguration serverQuicConfig = new ServerQuicConfiguration(sslContextFactory, pemWorkDir);
        QuicServerConnector connector = new QuicServerConnector(server, serverQuicConfig, new HTTP3ServerConnectionFactory(serverQuicConfig));
        connector.setPort(843);

        server.addConnector(connector);

        server.start();
        // end::h3[]
    }

    public void conscrypt()
    {
        // tag::conscrypt[]
        // Configure the JDK with the Conscrypt provider.
        Security.addProvider(new OpenSSLProvider());

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");
        // Configure Jetty's SslContextFactory to use the Conscrypt provider.
        sslContextFactory.setProvider("Conscrypt");
        // end::conscrypt[]
    }

    public void bouncyCastle()
    {
        // tag::bouncyCastle[]
        // Configure the JDK with the Bouncy Castle providers, you need both.
        Security.addProvider(new BouncyCastleProvider());
        Security.addProvider(new BouncyCastleJsseProvider());

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");
        // Configure Jetty's SslContextFactory to use the Bouncy Castle provider.
        sslContextFactory.setProvider("BCJSSE");
        // end::bouncyCastle[]
    }

    public void keyStoreScanner() throws Exception
    {
        // tag::keyStoreScanner[]
        Server server = new Server();

        // The HTTP configuration object.
        HttpConfiguration httpConfig = new HttpConfiguration();
        // Add the SecureRequestCustomizer because TLS is used.
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

        KeyStoreScanner keyStoreScanner = new KeyStoreScanner(sslContextFactory);
        keyStoreScanner.setScanInterval(60);
        server.addBean(keyStoreScanner);

        server.start();
        // end::keyStoreScanner[]
    }

    public void handlerTree()
    {
        class LoggingHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        }

        class App1Handler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        }

        class App2Handler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        }

        // tag::handlerTree[]
        Server server = new Server();

        GzipHandler gzipHandler = new GzipHandler();
        server.setHandler(gzipHandler);

        Handler.Sequence sequence = new Handler.Sequence();
        gzipHandler.setHandler(sequence);

        sequence.addHandler(new App1Handler());
        sequence.addHandler(new App2Handler());
        // end::handlerTree[]
    }

    public void handlerAPI()
    {
        class MyHandler extends Handler.Abstract
        {
            @Override
            // tag::handlerAPI[]
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            // end::handlerAPI[]
            {
                return false;
            }
        }
    }

    public void handlerHello() throws Exception
    {
        // tag::handlerHello[]
        class HelloWorldHandler extends Handler.Abstract.NonBlocking
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/html; charset=UTF-8");

                // Write a Hello World response.
                Content.Sink.write(response, true, """
                    <!DOCTYPE html>
                    <html>
                    <head>
                      <title>Jetty Hello World Handler</title>
                    </head>
                    <body>
                      <p>Hello World</p>
                    </body>
                    </html>
                    """, callback);
                return true;
            }
        }

        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Set the Hello World Handler.
        server.setHandler(new HelloWorldHandler());

        server.start();
        // end::handlerHello[]
    }

    public void handlerFilter() throws Exception
    {
        class HelloWorldHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                return true;
            }
        }

        // tag::handlerFilter[]
        class FilterHandler extends Handler.Wrapper
        {
            public FilterHandler(Handler handler)
            {
                super(handler);
            }

            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String path = Request.getPathInContext(request);
                if (path.startsWith("/old_path/"))
                {
                    // Rewrite old paths to new paths.
                    HttpURI uri = request.getHttpURI();
                    String newPath = "/new_path/" + path.substring("/old_path/".length());
                    HttpURI newURI = HttpURI.build(uri).path(newPath).asImmutable();

                    // Modify the request object by wrapping the HttpURI.
                    Request newRequest = Request.serveAs(request, newURI);

                    // Forward to the next Handler using the wrapped Request.
                    return super.handle(newRequest, response, callback);
                }
                else
                {
                    // Forward to the next Handler as-is.
                    return super.handle(request, response, callback);
                }
            }
        }

        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Link the Handlers in a chain.
        server.setHandler(new FilterHandler(new HelloWorldHandler()));

        server.start();
        // end::handlerFilter[]
    }

    public void handlerForm()
    {
        // tag::handlerForm[]
        class FormHandler extends Handler.Abstract.NonBlocking
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
                if (MimeTypes.Type.FORM_ENCODED.is(contentType))
                {
                    // Convert the request content into Fields.
                    FormFields.onFields(request, new Promise.Invocable<>() // <1>
                    {
                        @Override
                        public void succeeded(Fields fields) // <2>
                        {
                            processFields(fields);
                            // Send a simple 200 response, completing the callback.
                            response.setStatus(HttpStatus.OK_200);
                            callback.succeeded();
                        }

                        @Override
                        public void failed(Throwable failure)
                        {
                            // Reading the request content failed.
                            // Send an error response, completing the callback.
                            Response.writeError(request, response, callback, failure);
                        }

                        @Override
                        public InvocationType getInvocationType() // <3>
                        {
                            return InvocationType.NON_BLOCKING;
                        }
                    });

                    // The callback will be eventually completed in all cases, return true.
                    return true;
                }
                else
                {
                    // Send an error response, completing the callback, and returning true.
                    Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400, "invalid request");
                    return true;
                }
            }
        }
        // end::handlerForm[]
    }

    private static void processFields(Fields fields)
    {
    }

    public void handlerMultiPart()
    {
        // tag::handlerMultiPart[]
        class MultiPartFormDataHandler extends Handler.Abstract.NonBlocking
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
                if (MimeTypes.Type.MULTIPART_FORM_DATA.is(contentType))
                {
                    // Configuration for the MultiPart parser.
                    MultiPartConfig config = new MultiPartConfig.Builder()
                        // By default, uploaded files are stored in this directory, to
                        // avoid to read the file content (which can be large) in memory.
                        .location(Path.of("/tmp"))
                        .build();
                    // Convert the request content into parts.
                    MultiPartFormData.onParts(request, request, contentType, config, new Promise.Invocable<>() // <1>
                    {
                        @Override
                        public void succeeded(MultiPartFormData.Parts parts) // <2>
                        {
                            // Use the Parts API to process the parts.
                            processParts(parts);
                            // Send a simple 200 response, completing the callback.
                            response.setStatus(HttpStatus.OK_200);
                            callback.succeeded();
                        }

                        @Override
                        public void failed(Throwable failure)
                        {
                            // Reading the request content failed.
                            // Send an error response, completing the callback.
                            Response.writeError(request, response, callback, failure);
                        }

                        @Override
                        public InvocationType getInvocationType() // <3>
                        {
                            return InvocationType.NON_BLOCKING;
                        }
                    });

                    // The callback will be eventually completed in all cases, return true.
                    return true;
                }
                else
                {
                    // Send an error response, completing the callback, and returning true.
                    Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400, "invalid request");
                    return true;
                }
            }
        }
        // end::handlerMultiPart[]
    }

    private void processParts(MultiPartFormData.Parts parts)
    {
    }

    public void flush()
    {
        // tag::flush[]
        class FlushingHandler extends Handler.Abstract.NonBlocking
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Set the response status code.
                response.setStatus(HttpStatus.OK_200);
                // Set the response headers.
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");

                // Commit the response with a "flush" write.
                Callback.Completable.with(flush -> response.write(false, null, flush))
                    // When the flush is finished, send the content and complete the callback.
                    .whenComplete((ignored, failure) ->
                    {
                        if (failure == null)
                            response.write(true, UTF_8.encode("HELLO"), callback);
                        else
                            callback.failed(failure);
                    });

                // Return true because the callback will eventually be completed.
                return true;
            }
        }
        // end::flush[]
    }

    public void contentLength()
    {
        // tag::contentLength[]
        class ContentLengthHandler extends Handler.Abstract.NonBlocking
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Set the response status code.
                response.setStatus(HttpStatus.OK_200);

                String content = """
                    {
                      "result": 0,
                      "advice": {
                        "message": "Jetty Rocks!"
                      }
                    }
                    """;
                // Must count the bytes, not the characters!
                byte[] bytes = content.getBytes(UTF_8);
                long contentLength = bytes.length;

                // Set the response headers before the response is committed.
                HttpFields.Mutable responseHeaders = response.getHeaders();
                // Set the content type.
                responseHeaders.put(HttpHeader.CONTENT_TYPE, "application/json; charset=UTF-8");
                // Set the response content length.
                responseHeaders.put(HttpHeader.CONTENT_LENGTH, contentLength);

                // Commit the response.
                response.write(true, ByteBuffer.wrap(bytes), callback);

                // Return true because the callback will eventually be completed.
                return true;
            }
        }
        // end::contentLength[]
    }

    public void handlerContinue100()
    {
        // tag::continue[]
        class Continue100Handler extends Handler.Wrapper
        {
            public Continue100Handler(Handler handler)
            {
                super(handler);
            }

            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                HttpFields requestHeaders = request.getHeaders();
                if (requestHeaders.contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
                {
                    // Analyze the request and decide whether to receive the content.
                    long contentLength = request.getLength();
                    if (contentLength > 0 && contentLength < 1024)
                    {
                        // Small request content, ask to send it by
                        // sending a 100 Continue interim response.
                        CompletableFuture<Void> processing = response.writeInterim(HttpStatus.CONTINUE_100, HttpFields.EMPTY) // <1>
                            // Then read the request content into a ByteBuffer.
                            .thenCompose(ignored -> Promise.Completable.<ByteBuffer>with(p -> Content.Source.asByteBuffer(request, p)))
                            // Then store the ByteBuffer somewhere.
                            .thenCompose(byteBuffer -> store(byteBuffer));

                        // At the end of the processing, complete
                        // the callback with the CompletableFuture,
                        // a simple 200 response in case of success,
                        // or a 500 response in case of failure.
                        callback.completeWith(processing); // <2>
                        return true;
                    }
                    else
                    {
                        // The request content is too large, send an error.
                        Response.writeError(request, response, callback, HttpStatus.PAYLOAD_TOO_LARGE_413);
                        return true;
                    }
                }
                else
                {
                    return super.handle(request, response, callback);
                }
            }
        }
        // end::continue[]
    }

    private static CompletableFuture<Void> store(ByteBuffer byteBuffer)
    {
        return new CompletableFuture<>();
    }

    public void earlyHints()
    {
        // tag::earlyHints103[]
        class EarlyHints103Handler extends Handler.Wrapper
        {
            public EarlyHints103Handler(Handler handler)
            {
                super(handler);
            }

            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String pathInContext = Request.getPathInContext(request);

                // Simple logic that assumes that every HTML
                // file has associated the same CSS stylesheet.
                if (pathInContext.endsWith(".html"))
                {
                    // Tell the client that a Link is coming
                    // sending a 103 Early Hints interim response.
                    HttpFields.Mutable interimHeaders = HttpFields.build()
                        .put(HttpHeader.LINK, "</style.css>; rel=preload; as=style");

                    response.writeInterim(HttpStatus.EARLY_HINTS_103, interimHeaders) // <1>
                        .whenComplete((ignored, failure) -> // <2>
                        {
                            if (failure == null)
                            {
                                try
                                {
                                    // Delegate the handling to the child Handler.
                                    boolean handled = super.handle(request, response, callback);
                                    if (!handled)
                                    {
                                        // The child Handler did not produce a final response, do it here.
                                        Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
                                    }
                                }
                                catch (Throwable x)
                                {
                                    callback.failed(x);
                                }
                            }
                            else
                            {
                                callback.failed(failure);
                            }
                        });

                    // This Handler sent an interim response, so this Handler
                    // (or its descendants) must produce a final response, so return true.
                    return true;
                }
                else
                {
                    // Not a request for an HTML page, delegate
                    // the handling to the child Handler.
                    return super.handle(request, response, callback);
                }
            }
        }
        // end::earlyHints103[]
    }

    public void contextHandler() throws Exception
    {
        // tag::contextHandler[]
        class ShopHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Implement the shop, remembering to complete the callback.
                return true;
            }
        }

        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a ContextHandler with contextPath.
        ContextHandler context = new ContextHandler(new ShopHandler(), "/shop");

        // Link the context to the server.
        server.setHandler(context);

        server.start();
        // end::contextHandler[]
    }

    public void contextHandlerCollection() throws Exception
    {
        // tag::contextHandlerCollection[]
        class ShopHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Implement the shop, remembering to complete the callback.
                return true;
            }
        }

        class RESTHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Implement the REST APIs, remembering to complete the callback.
                return true;
            }
        }

        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();

        // Create the context for the shop web application and add it to ContextHandlerCollection.
        contextCollection.addHandler(new ContextHandler(new ShopHandler(), "/shop"));

        // Link the ContextHandlerCollection to the Server.
        server.setHandler(contextCollection);

        server.start();

        // Create the context for the API web application.
        ContextHandler apiContext = new ContextHandler(new RESTHandler(), "/api");
        // Web applications can be deployed after the Server is started.
        contextCollection.deployHandler(apiContext, Callback.NOOP);
        // end::contextHandlerCollection[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::servletContextHandler-servlet[]
    public class ShopCartServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
        {
            // Implement the shop cart functionality.
        }
    }
    // end::servletContextHandler-servlet[]

    public void servletContextHandler() throws Exception
    {
        // tag::servletContextHandler-setup[]
        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Add the CrossOriginHandler to protect from CSRF attacks.
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        crossOriginHandler.setAllowedOriginPatterns(Set.of("http://domain.com"));
        crossOriginHandler.setAllowCredentials(true);
        server.setHandler(crossOriginHandler);

        // Create a ServletContextHandler with contextPath.
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/shop");
        // Link the context to the server.
        crossOriginHandler.setHandler(context);

        // Add the Servlet implementing the cart functionality to the context.
        ServletHolder servletHolder = context.addServlet(ShopCartServlet.class, "/cart/*");
        // Configure the Servlet with init-parameters.
        servletHolder.setInitParameter("maxItems", "128");

        server.start();
        // end::servletContextHandler-setup[]
    }

    public void webAppContextHandler() throws Exception
    {
        // tag::webAppContextHandler[]
        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a WebAppContext.
        WebAppContext context = new WebAppContext();
        // Link the context to the server.
        server.setHandler(context);

        // Configure the path of the packaged web application (file or directory).
        context.setWar("/path/to/webapp.war");
        // Configure the contextPath.
        context.setContextPath("/app");

        server.start();
        // end::webAppContextHandler[]
    }

    public void resourceHandler() throws Exception
    {
        // tag::resourceHandler[]
        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create and configure a ResourceHandler.
        ResourceHandler handler = new ResourceHandler();
        // Configure the directory where static resources are located.
        handler.setBaseResource(ResourceFactory.of(handler).newResource("/path/to/static/resources/"));
        // Configure directory listing.
        handler.setDirAllowed(false);
        // Configure welcome files.
        handler.setWelcomeFiles(List.of("index.html"));
        // Configure whether to accept range requests.
        handler.setAcceptRanges(true);

        // Link the context to the server.
        server.setHandler(handler);

        server.start();
        // end::resourceHandler[]
    }

    public void multipleResourcesHandler()
    {
        // tag::multipleResourcesHandler[]
        ResourceHandler handler = new ResourceHandler();

        // For multiple directories, use ResourceFactory.combine().
        Resource resource = ResourceFactory.combine(
            ResourceFactory.of(handler).newResource("/path/to/static/resources/"),
            ResourceFactory.of(handler).newResource("/another/path/to/static/resources/")
        );
        handler.setBaseResource(resource);
        // end::multipleResourcesHandler[]
    }

    public void defaultServlet()
    {
        // tag::defaultServlet[]
        // Create a ServletContextHandler with contextPath.
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/app");

        // Add the DefaultServlet to serve static content.
        ServletHolder servletHolder = context.addServlet(DefaultServlet.class, "/");
        // Configure the DefaultServlet with init-parameters.
        servletHolder.setInitParameter("maxCacheSize", "8388608");
        servletHolder.setInitParameter("dirAllowed", "true");
        servletHolder.setAsyncSupported(true);
        // end::defaultServlet[]
    }

    public void resourceServlet()
    {
        // tag::resourceServlet[]
        // Create a ServletContextHandler with contextPath.
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/app");

        // Add the ResourceServlet to serve static content from a specific location.
        ServletHolder servletHolder = context.addServlet(ResourceServlet.class, "/static/*");
        // Configure the ResourceServlet with init-parameters.
        servletHolder.setInitParameter("baseResource", "/absolute/path/to/static/resources/");
        servletHolder.setInitParameter("pathInfoOnly", "true");
        servletHolder.setAsyncSupported(true);

        // Add the DefaultServlet to serve static content from the resource base
        ServletHolder defaultHolder = context.addServlet(DefaultServlet.class, "/");
        defaultHolder.setAsyncSupported(true);
        // end::resourceServlet[]
    }

    public void serverGzipHandler() throws Exception
    {
        // tag::serverGzipHandler[]
        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create and configure GzipHandler.
        GzipHandler gzipHandler = new GzipHandler();
        server.setHandler(gzipHandler);
        // Only compress response content larger than this.
        gzipHandler.setMinGzipSize(1024);
        // Do not compress these URI paths.
        gzipHandler.setExcludedPaths("/uncompressed");
        // Also compress POST responses.
        gzipHandler.addIncludedMethods("POST");
        // Do not compress these mime types.
        gzipHandler.addExcludedMimeTypes("font/ttf");

        // Create a ContextHandlerCollection to manage contexts.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        gzipHandler.setHandler(contexts);

        server.start();
        // end::serverGzipHandler[]
    }

    public void contextGzipHandler() throws Exception
    {
        class ShopHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Implement the shop, remembering to complete the callback.
                return true;
            }
        }

        class RESTHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Implement the REST APIs, remembering to complete the callback.
                return true;
            }
        }

        // tag::contextGzipHandler[]
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        // Link the ContextHandlerCollection to the Server.
        server.setHandler(contextCollection);

        // Create the context for the shop web application wrapped with GzipHandler so only the shop will do gzip.
        GzipHandler shopGzipHandler = new GzipHandler(new ContextHandler(new ShopHandler(), "/shop"));

        // Add it to ContextHandlerCollection.
        contextCollection.addHandler(shopGzipHandler);

        // Create the context for the API web application.
        ContextHandler apiContext = new ContextHandler(new RESTHandler(), "/api");

        // Add it to ContextHandlerCollection.
        contextCollection.addHandler(apiContext);

        server.start();
        // end::contextGzipHandler[]
    }

    public void rewriteHandler() throws Exception
    {
        // tag::rewriteHandler[]
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create and link the RewriteHandler to the Server.
        RewriteHandler rewriteHandler = new RewriteHandler();
        server.setHandler(rewriteHandler);

        // Compacts URI paths with double slashes, e.g. /ctx//path/to//resource.
        rewriteHandler.addRule(new CompactPathRule());
        // Rewrites */products/* to */p/*.
        rewriteHandler.addRule(new RewriteRegexRule("/(.*)/product/(.*)", "/$1/p/$2"));
        // Redirects permanently to a different URI.
        RedirectRegexRule redirectRule = new RedirectRegexRule("/documentation/(.*)", "https://docs.domain.com/$1");
        redirectRule.setStatusCode(HttpStatus.MOVED_PERMANENTLY_301);
        rewriteHandler.addRule(redirectRule);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        rewriteHandler.setHandler(contextCollection);

        server.start();
        // end::rewriteHandler[]
    }

    public void statisticsHandler() throws Exception
    {
        // tag::statisticsHandler[]
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create and link the StatisticsHandler to the Server.
        StatisticsHandler statsHandler = new StatisticsHandler();
        server.setHandler(statsHandler);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        statsHandler.setHandler(contextCollection);

        server.start();
        // end::statisticsHandler[]
    }

    public void dataRateHandler() throws Exception
    {
        // tag::dataRateHandler[]
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create and link the MinimumDataRateHandler to the Server.
        // Create the MinimumDataRateHandler with a minimum read rate of 1KB per second and no minimum write rate.
        StatisticsHandler.MinimumDataRateHandler dataRateHandler = new StatisticsHandler.MinimumDataRateHandler(1024L, 0L);
        server.setHandler(dataRateHandler);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        dataRateHandler.setHandler(contextCollection);

        server.start();
        // end::dataRateHandler[]
    }

    public void eventsHandler() throws Exception
    {
        // tag::eventsHandler[]
        class MyEventsHandler extends EventsHandler
        {
            @Override
            protected void onBeforeHandling(Request request)
            {
                // The nanoTime at which the request is first received.
                long requestBeginNanoTime = request.getBeginNanoTime();

                // The nanoTime just before invoking the next Handler.
                request.setAttribute("beforeHandlingNanoTime", NanoTime.now());
            }

            @Override
            protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
            {
                // Retrieve the before handling nanoTime.
                long beforeHandlingNanoTime = (long)request.getAttribute("beforeHandlingNanoTime");

                // Record the request processing time and the status that was sent back to the client.
                long processingTime = NanoTime.millisSince(beforeHandlingNanoTime);
                System.getLogger("trackTime").log(INFO, "processing request %s took %d ms and ended with status code %d", request, processingTime, status);
            }
        }

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Link the EventsHandler as the outermost Handler after Server.
        MyEventsHandler eventsHandler = new MyEventsHandler();
        server.setHandler(eventsHandler);

        ContextHandler appHandler = new ContextHandler("/app");
        eventsHandler.setHandler(appHandler);

        server.start();
        // end::eventsHandler[]
    }

    public void simpleQoSHandler() throws Exception
    {
        // tag::simpleQoSHandler[]
        class ShopHandler extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Implement the shop, remembering to complete the callback.
                callback.succeeded();
                return true;
            }
        }

        int maxThreads = 256;
        QueuedThreadPool serverThreads = new QueuedThreadPool(maxThreads);
        Server server = new Server(serverThreads);
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create and configure QoSHandler.
        QoSHandler qosHandler = new QoSHandler();
        // Set the max number of concurrent requests,
        // for example in relation to the thread pool.
        qosHandler.setMaxRequestCount(maxThreads / 2);
        // A suspended request may stay suspended for at most 5 seconds.
        qosHandler.setMaxSuspend(Duration.ofSeconds(5));
        server.setHandler(qosHandler);

        // Provide quality of service to the shop
        // application by wrapping ShopHandler with QoSHandler.
        qosHandler.setHandler(new ShopHandler());

        server.start();
        // end::simpleQoSHandler[]
    }

    public void advancedQoSHandler()
    {
        // tag::advancedQoSHandler[]
        class PriorityQoSHandler extends QoSHandler
        {
            @Override
            protected int getPriority(Request request)
            {
                String pathInContext = Request.getPathInContext(request);

                // Payment requests have the highest priority.
                if (pathInContext.startsWith("/payment/"))
                    return 3;

                // Login, checkout and admin requests.
                if (pathInContext.startsWith("/login/"))
                    return 2;
                if (pathInContext.startsWith("/checkout/"))
                    return 2;
                if (pathInContext.startsWith("/admin/"))
                    return 2;

                // Health-check requests from the load balancer.
                if (pathInContext.equals("/health-check"))
                    return 1;

                // Other requests have the lowest priority.
                return 0;
            }
        }
        // end::advancedQoSHandler[]
    }

    public void securedHandler() throws Exception
    {
        // tag::securedHandler[]
        Server server = new Server();

        // Configure the HttpConfiguration for the clear-text connector.
        int securePort = 8443;
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecurePort(securePort);

        // The clear-text connector.
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(8080);
        server.addConnector(connector);

        // Configure the HttpConfiguration for the secure connector.
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        // Add the SecureRequestCustomizer because TLS is used.
        httpConfig.addCustomizer(new SecureRequestCustomizer());

        // The HttpConnectionFactory for the secure connector.
        HttpConnectionFactory http11 = new HttpConnectionFactory(httpsConfig);

        // Configure the SslContextFactory with the keyStore information.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");

        // The ConnectionFactory for TLS.
        SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, http11.getProtocol());

        // The secure connector.
        ServerConnector secureConnector = new ServerConnector(server, tls, http11);
        secureConnector.setPort(8443);
        server.addConnector(secureConnector);

        // Create and link the SecuredRedirectHandler to the Server.
        SecuredRedirectHandler securedHandler = new SecuredRedirectHandler();
        server.setHandler(securedHandler);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        securedHandler.setHandler(contextCollection);

        server.start();
        // end::securedHandler[]
    }

    public void crossOriginAllowedOrigins()
    {
        // tag::crossOriginAllowedOrigins[]
        CrossOriginHandler crossOriginHandler = new CrossOriginHandler();
        // The allowed origins are regex patterns.
        crossOriginHandler.setAllowedOriginPatterns(Set.of("http://domain\\.com"));
        // end::crossOriginAllowedOrigins[]
    }

    public void stateTrackingHandle()
    {
        // tag::stateTrackingHandle[]
        StateTrackingHandler stateTrackingHandler = new StateTrackingHandler();

        // Emit an event if the Handler callback is not completed with 5 seconds.
        stateTrackingHandler.setHandlerCallbackTimeout(5000);
        // end::stateTrackingHandle[]
    }

    public void stateTrackingListener()
    {
        // tag::stateTrackingListener[]
        StateTrackingHandler stateTrackingHandler = new StateTrackingHandler(new StateTrackingHandler.Listener()
        {
            @Override
            public void onHandlerCallbackNotCompleted(Request request, StateTrackingHandler.ThreadInfo handlerThreadInfo)
            {
                // Your own event handling logic.
            }
        });

        // Emit an event if the Handler callback is not completed with 5 seconds.
        stateTrackingHandler.setHandlerCallbackTimeout(5000);
        // end::stateTrackingListener[]
    }

    public void defaultHandler() throws Exception
    {
        // tag::defaultHandler[]
        Server server = new Server();
        server.setDefaultHandler(new DefaultHandler(false, true));

        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Add a ContextHandlerCollection to manage contexts.
        ContextHandlerCollection contexts = new ContextHandlerCollection();

        // Link the contexts to the Server.
        server.setHandler(contexts);

        server.start();
        // end::defaultHandler[]
    }

    public void gracefulHandler() throws Exception
    {
        // tag::gracefulHandler[]
        Server server = new Server();

        // Install the GracefulHandler.
        GracefulHandler gracefulHandler = new GracefulHandler();
        server.setHandler(gracefulHandler);

        // Set the Server stopTimeout to wait at most
        // 10 seconds for existing requests to complete.
        server.setStopTimeout(10_000);

        // Add one web application.
        class MyWebApp extends Handler.Abstract
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Implement your web application.
                callback.succeeded();
                return true;
            }
        }

        ContextHandler contextHandler = new ContextHandler(new MyWebApp(), "/app");
        gracefulHandler.setHandler(contextHandler);

        server.start();
        // end::gracefulHandler[]
    }

    public void continue100()
    {
        // tag::continue100[]
        class Continue100HttpServlet extends HttpServlet
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Inspect the method and headers.
                boolean isPost = HttpMethod.POST.is(request.getMethod());
                boolean expects100 = HttpHeaderValue.CONTINUE.is(request.getHeader("Expect"));
                long contentLength = request.getContentLengthLong();

                if (isPost && expects100)
                {
                    if (contentLength > 1024 * 1024)
                    {
                        // Rejects uploads that are too large.
                        response.sendError(HttpStatus.PAYLOAD_TOO_LARGE_413);
                    }
                    else
                    {
                        // Getting the request InputStream indicates that
                        // the application wants to read the request content.
                        // Jetty will send the 100 Continue response at this
                        // point, and the client will send the request content.
                        ServletInputStream input = request.getInputStream();

                        // Read and process the request input.
                    }
                }
                else
                {
                    // Process normal requests.
                }
            }
        }
        // end::continue100[]
    }

    public void requestCustomizer() throws Exception
    {
        // tag::requestCustomizer[]
        Server server = new Server();

        // Configure the secure connector.
        HttpConfiguration httpsConfig = new HttpConfiguration();

        // Add the SecureRequestCustomizer.
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        // Configure the SslContextFactory with the KeyStore information.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");
        // Configure the Connector to speak HTTP/1.1 and HTTP/2.
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpsConfig);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        ServerConnector connector = new ServerConnector(server, ssl, alpn, h2, h1);
        server.addConnector(connector);

        server.start();
        // end::requestCustomizer[]
    }
}
