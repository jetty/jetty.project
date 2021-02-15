//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class SecuredRedirectHandlerTest
{
    private static Server server;
    private static HostnameVerifier origVerifier;
    private static SSLSocketFactory origSsf;
    private static URI serverHttpUri;
    private static URI serverHttpsUri;

    @BeforeAll
    public static void startServer() throws Exception
    {
        // Setup SSL
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");
        sslContextFactory.setTrustStorePath(keystore.getAbsolutePath());
        sslContextFactory.setTrustStorePassword("storepwd");

        server = new Server();

        int port = 32080;
        int securePort = 32443;

        // Setup HTTP Configuration
        HttpConfiguration httpConf = new HttpConfiguration();
        httpConf.setSecurePort(securePort);
        httpConf.setSecureScheme("https");

        ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(httpConf));
        httpConnector.setName("unsecured");
        httpConnector.setPort(port);

        // Setup HTTPS Configuration
        HttpConfiguration httpsConf = new HttpConfiguration(httpConf);
        httpsConf.addCustomizer(new SecureRequestCustomizer());

        ServerConnector httpsConnector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpsConf));
        httpsConnector.setName("secured");
        httpsConnector.setPort(securePort);

        // Add connectors
        server.setConnectors(new Connector[]{httpConnector, httpsConnector});

        // Wire up contexts
        String[] secureHosts = new String[]{"@secured"};

        ContextHandler test1Context = new ContextHandler();
        test1Context.setContextPath("/test1");
        test1Context.setHandler(new HelloHandler("Hello1"));
        test1Context.setVirtualHosts(secureHosts);

        ContextHandler test2Context = new ContextHandler();
        test2Context.setContextPath("/test2");
        test2Context.setHandler(new HelloHandler("Hello2"));
        test2Context.setVirtualHosts(secureHosts);

        ContextHandler rootContext = new ContextHandler();
        rootContext.setContextPath("/");
        rootContext.setHandler(new RootHandler("/test1", "/test2"));
        rootContext.setVirtualHosts(secureHosts);

        // Wire up context for unsecure handling to only
        // the named 'unsecured' connector
        ContextHandler redirectHandler = new ContextHandler();
        redirectHandler.setContextPath("/");
        redirectHandler.setHandler(new SecuredRedirectHandler());
        redirectHandler.setVirtualHosts(new String[]{"@unsecured"});

        // Establish all handlers that have a context
        ContextHandlerCollection contextHandlers = new ContextHandlerCollection();
        contextHandlers.setHandlers(new Handler[]{redirectHandler, rootContext, test1Context, test2Context});

        // Create server level handler tree
        HandlerList handlers = new HandlerList();
        handlers.addHandler(contextHandlers);
        handlers.addHandler(new DefaultHandler()); // round things out

        server.setHandler(handlers);

        server.start();

        // calculate serverUri
        String host = httpConnector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        serverHttpUri = new URI(String.format("http://%s:%d/", host, httpConnector.getLocalPort()));
        serverHttpsUri = new URI(String.format("https://%s:%d/", host, httpsConnector.getLocalPort()));

        origVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        origSsf = HttpsURLConnection.getDefaultSSLSocketFactory();

        HttpsURLConnection.setDefaultHostnameVerifier(new AllowAllVerifier());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContextFactory.getSslContext().getSocketFactory());
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        HttpsURLConnection.setDefaultSSLSocketFactory(origSsf);
        HttpsURLConnection.setDefaultHostnameVerifier(origVerifier);

        server.stop();
        server.join();
    }

    @Test
    public void testRedirectUnsecuredRoot() throws Exception
    {
        URL url = serverHttpUri.resolve("/").toURL();
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setAllowUserInteraction(false);
        assertThat("response code", connection.getResponseCode(), is(302));
        assertThat("location header", connection.getHeaderField("Location"), is(serverHttpsUri.resolve("/").toASCIIString()));
        connection.disconnect();
    }

    @Test
    public void testRedirectSecuredRoot() throws Exception
    {
        URL url = serverHttpsUri.resolve("/").toURL();
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setAllowUserInteraction(false);
        assertThat("response code", connection.getResponseCode(), is(200));
        String content = getContent(connection);
        assertThat("response content", content, containsString("<a href=\"/test1\">"));
        connection.disconnect();
    }

    @Test
    public void testAccessUnsecuredHandler() throws Exception
    {
        URL url = serverHttpUri.resolve("/test1").toURL();
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setAllowUserInteraction(false);
        assertThat("response code", connection.getResponseCode(), is(302));
        assertThat("location header", connection.getHeaderField("Location"), is(serverHttpsUri.resolve("/test1").toASCIIString()));
        connection.disconnect();
    }

    @Test
    public void testAccessUnsecured404() throws Exception
    {
        URL url = serverHttpUri.resolve("/nothing/here").toURL();
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setAllowUserInteraction(false);
        assertThat("response code", connection.getResponseCode(), is(302));
        assertThat("location header", connection.getHeaderField("Location"), is(serverHttpsUri.resolve("/nothing/here").toASCIIString()));
        connection.disconnect();
    }

    @Test
    public void testAccessSecured404() throws Exception
    {
        URL url = serverHttpsUri.resolve("/nothing/here").toURL();
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setAllowUserInteraction(false);
        assertThat("response code", connection.getResponseCode(), is(404));
        connection.disconnect();
    }

    private String getContent(HttpURLConnection connection) throws IOException
    {
        try (InputStream in = connection.getInputStream();
             InputStreamReader reader = new InputStreamReader(in))
        {
            StringWriter writer = new StringWriter();
            IO.copy(reader, writer);
            return writer.toString();
        }
    }

    public static class HelloHandler extends AbstractHandler
    {
        private final String msg;

        public HelloHandler(String msg)
        {
            this.msg = msg;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.setContentType("text/plain");
            response.getWriter().printf("%s%n", msg);
            baseRequest.setHandled(true);
        }
    }

    public static class RootHandler extends AbstractHandler
    {
        private final String[] childContexts;

        public RootHandler(String... children)
        {
            this.childContexts = children;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (!"/".equals(target))
            {
                baseRequest.setHandled(true);
                response.sendError(404);
                return;
            }

            response.setContentType("text/html");
            PrintWriter out = response.getWriter();
            out.println("<html>");
            out.println("<head><title>Contexts</title></head>");
            out.println("<body>");
            out.println("<h4>Child Contexts</h4>");
            out.println("<ul>");
            for (String child : childContexts)
            {
                out.printf("<li><a href=\"%s\">%s</a></li>%n", child, child);
            }
            out.println("</ul>");
            out.println("</body></html>");
            baseRequest.setHandled(true);
        }
    }
}
