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

package org.eclipse.jetty.docs.programming.server.http;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.TimeZone;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlets.CrossOriginFilter;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.rewrite.handler.CompactPathRule;
import org.eclipse.jetty.rewrite.handler.RedirectRegexRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.rewrite.handler.RewriteRegexRule;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
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
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.SecuredRedirectHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;
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
        server.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                // Succeed the callback to write the response.
                callback.succeeded();
            }
        });

        // Start the Server so it starts accepting connections from clients.
        server.start();
        // end::simple[]
    }

    public void httpChannelListener() throws Exception
    {
        // tag::httpChannelListener[]
        // TODO: HttpChannelState.Listener does not exist anymore.
/*
        class TimingHttpChannelListener implements HttpChannelState.Listener
        {
            private final ConcurrentMap<Request, Long> times = new ConcurrentHashMap<>();

            @Override
            public void onRequestBegin(Request request)
            {
                times.put(request, System.nanoTime());
            }

            @Override
            public void onComplete(Request request)
            {
                long begin = times.remove(request);
                long elapsed = System.nanoTime() - begin;
                System.getLogger("timing").log(INFO, "Request {0} took {1} ns", request, elapsed);
            }
        }

        Server server = new Server();

        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Add the HttpChannel.Listener as bean to the connector.
        connector.addBean(new TimingHttpChannelListener());

        // Set a simple Handler to handle requests/responses.
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                jettyRequest.setHandled(true);
            }
        });

        server.start();
*/
        // end::httpChannelListener[]
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

    public void contextRequestLog()
    {
        // tag::contextRequestLog[]
        Server server = new Server();

        // Create a first ServletContextHandler for your main application.
        ServletContextHandler mainContext = new ServletContextHandler();
        mainContext.setContextPath("/main");

        // Create a RequestLogHandler to log requests for your main application.
        // TODO: RequestLogHandler may need to be re-introduced, to allow per-context logging?
/*
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        requestLogHandler.setRequestLog(new CustomRequestLog());
        // Wrap the main application with the request log handler.
        requestLogHandler.setHandler(mainContext);

        // Create a second ServletContextHandler for your other application.
        // No request logging for this application.
        ServletContextHandler otherContext = new ServletContextHandler();
        mainContext.setContextPath("/other");

        server.setHandler(new HandlerList(requestLogHandler, otherContext));
*/
        // end::contextRequestLog[]
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
        HTTP3ServerConnector connector = new HTTP3ServerConnector(server, sslContextFactory, new HTTP3ServerConnectionFactory(httpConfig));
        connector.setPort(843);
        server.addConnector(connector);

        server.start();
        // end::h3[]
    }

    public void handlerTree()
    {
        class LoggingHandler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                callback.succeeded();
            }
        }

        class App1Handler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                callback.succeeded();
            }
        }

        class App2Handler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                callback.succeeded();
            }
        }

        // tag::handlerTree[]
        // Create a Server instance.
        Server server = new Server();

        LoggingHandler loggingHandler = new LoggingHandler();
        // Link the root Handler with the Server.
        server.setHandler(loggingHandler);

        Handler.Collection collection = new Handler.Collection();
        collection.addHandler(new App1Handler());
        collection.addHandler(new App2Handler());
        // end::handlerTree[]
    }

    public void handlerAPI()
    {
        class MyHandler extends Handler.Processor
        {
            @Override
            // tag::handlerAPI[]
            public void process(Request request, Response response, Callback callback) throws Exception
            {
            }
            // end::handlerAPI[]
        }
    }

    public void handlerHello() throws Exception
    {
        // tag::handlerHello[]
        class HelloWorldHandler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback)
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
        class HelloWorldHandler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
            }
        }

        // tag::handlerFilter[]
        // TODO: This needs to be rewritten using a custom Processor
/*
        class FilterHandler extends HandlerWrapper
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                String path = request.getRequestURI();
                if (path.startsWith("/old_path/"))
                {
                    // Rewrite old paths to new paths.
                    HttpURI uri = jettyRequest.getHttpURI();
                    String newPath = "/new_path/" + path.substring("/old_path/".length());
                    HttpURI newURI = HttpURI.build(uri).path(newPath);
                    // Modify the request object.
                    jettyRequest.setHttpURI(newURI);
                }

                // This Handler is not handling the request, so
                // it does not call jettyRequest.setHandled(true).

                // Forward to the next Handler.
                super.handle(target, jettyRequest, request, response);
            }
        }

        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Link the Handlers.
        FilterHandler filter = new FilterHandler();
        filter.setHandler(new HelloWorldHandler());
        server.setHandler(filter);

        server.start();
*/
        // end::handlerFilter[]
    }

    public void contextHandler() throws Exception
    {
        // tag::contextHandler[]
        class ShopHandler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                // Implement the shop, remembering to complete the callback.
            }
        }

        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a ContextHandler with contextPath.
        ContextHandler context = new ContextHandler();
        context.setContextPath("/shop");
        context.setHandler(new ShopHandler());

        // Link the context to the server.
        server.setHandler(context);

        server.start();
        // end::contextHandler[]
    }

    public void contextHandlerCollection() throws Exception
    {
        // tag::contextHandlerCollection[]
        class ShopHandler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                // Implement the shop, remembering to complete the callback.
            }
        }

        class RESTHandler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                // Implement the REST APIs, remembering to complete the callback.
            }
        }

        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        // Link the ContextHandlerCollection to the Server.
        server.setHandler(contextCollection);

        // Create the context for the shop web application.
        ContextHandler shopContext = new ContextHandler("/shop");
        shopContext.setHandler(new ShopHandler());
        // Add it to ContextHandlerCollection.
        contextCollection.addHandler(shopContext);

        server.start();

        // Create the context for the API web application.
        ContextHandler apiContext = new ContextHandler("/api");
        apiContext.setHandler(new RESTHandler());
        // Web applications can be deployed after the Server is started.
        contextCollection.deployHandler(apiContext, Callback.NOOP);
        // end::contextHandlerCollection[]
    }

    public void servletContextHandler() throws Exception
    {
        // tag::servletContextHandler[]
        class ShopCartServlet extends HttpServlet
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                // Implement the shop cart functionality.
            }
        }

        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a ServletContextHandler with contextPath.
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/shop");

        // Add the Servlet implementing the cart functionality to the context.
        ServletHolder servletHolder = context.addServlet(ShopCartServlet.class, "/cart/*");
        // Configure the Servlet with init-parameters.
        servletHolder.setInitParameter("maxItems", "128");

        // Add the CrossOriginFilter to protect from CSRF attacks.
        FilterHolder filterHolder = context.addFilter(CrossOriginFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        // Configure the filter.
        filterHolder.setAsyncSupported(true);

        // Link the context to the server.
        server.setHandler(context);

        server.start();
        // end::servletContextHandler[]
    }

    public void webAppContextHandler() throws Exception
    {
        // tag::webAppContextHandler[]
        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a WebAppContext.
        WebAppContext context = new WebAppContext();
        // Configure the path of the packaged web application (file or directory).
        context.setWar("/path/to/webapp.war");
        // Configure the contextPath.
        context.setContextPath("/app");

        // Link the context to the server.
        server.setHandler(context);

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
        handler.setBaseResource(Resource.newResource("/path/to/static/resources/"));
        // Configure directory listing.
        // TODO: is the directoriesListed feature still present?
//        handler.setDirectoriesListed(false);
        // Configure welcome files.
        handler.setWelcomeFiles(List.of("index.html"));
        // Configure whether to accept range requests.
        handler.setAcceptRanges(true);

        // Link the context to the server.
        server.setHandler(handler);

        server.start();
        // end::resourceHandler[]
    }

    public void multipleResourcesHandler() throws Exception
    {
        // tag::multipleResourcesHandler[]
        ResourceHandler handler = new ResourceHandler();

        // For multiple directories, use ResourceCollection.
        Resource directories = Resource.of(
            Resource.newResource("/path/to/static/resources/"),
            Resource.newResource("/another/path/to/static/resources/")
        );

        handler.setBaseResource(directories);
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
        servletHolder.setInitParameter("resourceBase", "/path/to/static/resources/");
        servletHolder.setAsyncSupported(true);
        // end::defaultServlet[]
    }

    public void serverGzipHandler() throws Exception
    {
        // tag::serverGzipHandler[]
        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create and configure GzipHandler.
        GzipHandler gzipHandler = new GzipHandler();
        // Only compress response content larger than this.
        gzipHandler.setMinGzipSize(1024);
        // Do not compress these URI paths.
        gzipHandler.setExcludedPaths("/uncompressed");
        // Also compress POST responses.
        gzipHandler.addIncludedMethods("POST");
        // Do not compress these mime types.
        gzipHandler.addExcludedMimeTypes("font/ttf");

        // Link a ContextHandlerCollection to manage contexts.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        gzipHandler.setHandler(contexts);

        // Link the GzipHandler to the Server.
        server.setHandler(gzipHandler);

        server.start();
        // end::serverGzipHandler[]
    }

    public void contextGzipHandler() throws Exception
    {
        class ShopHandler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                // Implement the shop, remembering to complete the callback.
            }
        }

        class RESTHandler extends Handler.Processor
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                // Implement the REST APIs, remembering to complete the callback.
            }
        }

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        // tag::contextGzipHandler[]
        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        // Link the ContextHandlerCollection to the Server.
        server.setHandler(contextCollection);

        // Create the context for the shop web application.
        ContextHandler shopContext = new ContextHandler("/shop");
        shopContext.setHandler(new ShopHandler());

        // You want to gzip the shop web application only.
        GzipHandler shopGzipHandler = new GzipHandler();
        shopGzipHandler.setHandler(shopContext);

        // Add it to ContextHandlerCollection.
        contextCollection.addHandler(shopGzipHandler);

        // Create the context for the API web application.
        ContextHandler apiContext = new ContextHandler("/api");
        apiContext.setHandler(new RESTHandler());

        // Add it to ContextHandlerCollection.
        contextCollection.addHandler(apiContext);
        // end::contextGzipHandler[]

        server.start();
    }

    public void rewriteHandler() throws Exception
    {
        // tag::rewriteHandler[]
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        RewriteHandler rewriteHandler = new RewriteHandler();
        // Compacts URI paths with double slashes, e.g. /ctx//path/to//resource.
        rewriteHandler.addRule(new CompactPathRule());
        // Rewrites */products/* to */p/*.
        rewriteHandler.addRule(new RewriteRegexRule("/(.*)/product/(.*)", "/$1/p/$2"));
        // Redirects permanently to a different URI.
        RedirectRegexRule redirectRule = new RedirectRegexRule("/documentation/(.*)", "https://docs.domain.com/$1");
        redirectRule.setStatusCode(HttpStatus.MOVED_PERMANENTLY_301);
        rewriteHandler.addRule(redirectRule);

        // Link the RewriteHandler to the Server.
        server.setHandler(rewriteHandler);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        // Link the ContextHandlerCollection to the RewriteHandler.
        rewriteHandler.setHandler(contextCollection);

        server.start();
        // end::rewriteHandler[]
    }

    public void statsHandler() throws Exception
    {
        // tag::statsHandler[]
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        StatisticsHandler statsHandler = new StatisticsHandler();

        // Link the StatisticsHandler to the Server.
        server.setHandler(statsHandler);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        // Link the ContextHandlerCollection to the StatisticsHandler.
        statsHandler.setHandler(contextCollection);

        server.start();
        // end::statsHandler[]
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

        // Configure the HttpConfiguration for the encrypted connector.
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        // Add the SecureRequestCustomizer because we are using TLS.
        httpConfig.addCustomizer(new SecureRequestCustomizer());

        // The HttpConnectionFactory for the encrypted connector.
        HttpConnectionFactory http11 = new HttpConnectionFactory(httpsConfig);

        // Configure the SslContextFactory with the keyStore information.
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("/path/to/keystore");
        sslContextFactory.setKeyStorePassword("secret");

        // The ConnectionFactory for TLS.
        SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, http11.getProtocol());

        // The encrypted connector.
        ServerConnector secureConnector = new ServerConnector(server, tls, http11);
        secureConnector.setPort(8443);
        server.addConnector(secureConnector);

        SecuredRedirectHandler securedHandler = new SecuredRedirectHandler();

        // Link the SecuredRedirectHandler to the Server.
        server.setHandler(securedHandler);

        // Create a ContextHandlerCollection to hold contexts.
        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        // Link the ContextHandlerCollection to the StatisticsHandler.
        securedHandler.setHandler(contextCollection);

        server.start();
        // end::securedHandler[]
    }

    public void defaultHandler() throws Exception
    {
        // tag::defaultHandler[]
        Server server = new Server();
        Connector connector = new ServerConnector(server);
        server.addConnector(connector);

        // Create a Handler collection.
        Handler.Collection handlerList = new Handler.Collection();

        // Add as first a ContextHandlerCollection to manage contexts.
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        handlerList.addHandler(contexts);

        // Add as last a DefaultHandler.
        DefaultHandler defaultHandler = new DefaultHandler();
        handlerList.addHandler(defaultHandler);

        // Link the HandlerList to the Server.
        server.setHandler(handlerList);

        server.start();
        // end::defaultHandler[]
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
}
