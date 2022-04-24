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

package org.eclipse.jetty.ee10.demos;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlets.PushCacheFilter;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.LoggerFactory;

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

        server.addBean(LoggerFactory.getILoggerFactory());

        ServletContextHandler context = new ServletContextHandler(server, "/", ServletContextHandler.SESSIONS);
        Path docroot = Paths.get("src/main/resources/docroot");
        if (!Files.exists(docroot))
            throw new FileNotFoundException(docroot.toString());

        context.setResourceBase(docroot);
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
        Path keystorePath = Paths.get("src/main/resources/etc/keystore.p12").toAbsolutePath();
        if (!Files.exists(keystorePath))
            throw new FileNotFoundException(keystorePath.toString());
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath.toString());
        sslContextFactory.setKeyStorePassword("storepwd");
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

        server.start();
        server.join();
    }

    public static class PushedTilesFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig)
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            /* TODO
            Request baseRequest = Request.getBaseRequest(request);

            if (baseRequest.isPush() && baseRequest.getRequestURI().contains("tiles"))
            {
                String uri = baseRequest.getRequestURI().replace("tiles", "pushed").substring(baseRequest.getContextPath().length());
                request.getRequestDispatcher(uri).forward(request, response);
                return;
            }

             */

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
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
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

            content += Optional.ofNullable(request.getCookies())
                .stream()
                .flatMap(Arrays::stream)
                .map(cookie -> String.format("cookie %s=%s", cookie.getName(), cookie.getValue()))
                .collect(Collectors.joining(System.lineSeparator()));

            response.setContentLength(content.length());
            response.getOutputStream().print(content);
        }
    };
}
