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

package org.eclipse.jetty.embedded;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.PushCacheFilter;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 *
 */
public class Http2Server
{
    public static void main(String... args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        int securePort = ExampleUtil.getPort(args, "jetty.https.port", 8443);
        Server server = new Server();

        MBeanContainer mbContainer = new MBeanContainer(
            ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);

        ServletContextHandler context = new ServletContextHandler(server, "/", ServletContextHandler.SESSIONS);
        Path docroot = Paths.get("src/main/resources/docroot");
        if (!Files.exists(docroot))
            throw new FileNotFoundException(docroot.toString());

        context.setBaseResource(new PathResource(docroot));
        context.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        // context.addFilter(PushSessionCacheFilter.class,"/*",EnumSet.of(DispatcherType.REQUEST));
        context.addFilter(PushedTilesFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(servlet), "/test/*");
        context.addServlet(DefaultServlet.class, "/").setInitParameter("maxCacheSize", "81920");
        server.setHandler(context);

        // HTTP Configuration
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(securePort);
        httpConfig.setSendXPoweredBy(true);
        httpConfig.setSendServerVersion(true);

        // HTTP Connector
        ServerConnector http = new ServerConnector(server, new HttpConnectionFactory(httpConfig), new HTTP2CServerConnectionFactory(httpConfig));
        http.setPort(port);
        server.addConnector(http);

        // SSL Context Factory for HTTPS and HTTP/2
        Path keystorePath = Paths.get("src/main/resources/etc/keystore").toAbsolutePath();
        if (!Files.exists(keystorePath))
            throw new FileNotFoundException(keystorePath.toString());
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath.toString());
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
        // sslContextFactory.setProvider("Conscrypt");

        // HTTPS Configuration
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        // HTTP/2 Connection Factory
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);

        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(http.getDefaultProtocol());

        // SSL Connection Factory
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());

        // HTTP/2 Connector
        ServerConnector http2Connector =
            new ServerConnector(server, ssl, alpn, h2, new HttpConnectionFactory(httpsConfig));
        http2Connector.setPort(securePort);
        server.addConnector(http2Connector);

        ALPN.debug = false;

        server.start();
        server.join();
    }

    public static class PushedTilesFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            Request baseRequest = Request.getBaseRequest(request);

            if (baseRequest.isPush() && baseRequest.getRequestURI().contains("tiles"))
            {
                String uri = baseRequest.getRequestURI().replace("tiles", "pushed").substring(baseRequest.getContextPath().length());
                request.getRequestDispatcher(uri).forward(request, response);
                return;
            }

            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
        }
    }

    static Servlet servlet = new HttpServlet()
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String code = request.getParameter("code");
            if (code != null)
                response.setStatus(Integer.parseInt(code));

            HttpSession session = request.getSession(true);
            if (session.isNew())
                response.addCookie(new Cookie("bigcookie",
                    "This is a test cookies that was created on " + new Date() + " and is used by the jetty http/2 test servlet."));
            response.setHeader("Custom", "Value");
            response.setContentType("text/plain");
            String content = "Hello from Jetty using " + request.getProtocol() + "\n";
            content += "uri=" + request.getRequestURI() + "\n";
            content += "session=" + session.getId() + (session.isNew() ? "(New)\n" : "\n");
            content += "date=" + new Date() + "\n";

            Cookie[] cookies = request.getCookies();
            if (cookies != null && cookies.length > 0)
                for (Cookie c : cookies)
                {
                    content += "cookie " + c.getName() + "=" + c.getValue() + "\n";
                }

            response.setContentLength(content.length());
            response.getOutputStream().print(content);
        }
    };
}
