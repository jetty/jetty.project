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

package org.eclipse.jetty.servlet6.embedded;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.ee11.servlet.DefaultServlet;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
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

public class EarlyHintServer
{
    private static final Random random = new Random();
    private static final Set<String> thinking = new ConcurrentSkipListSet<>();

    public static void main(String... args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        int securePort = ExampleUtil.getPort(args, "jetty.https.port", 8443);
        Server server = new Server();

        MBeanContainer mbContainer = new MBeanContainer(
            ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);

        server.addBean(LoggerFactory.getILoggerFactory());

        ServletContextHandler context = new ServletContextHandler("/", ServletContextHandler.SESSIONS);

        Path embeddedDir = JettyDemos.JETTY_DEMOS_DIR.resolve("jetty-servlet6-demos/jetty-servlet6-demo-embedded");
        Path docroot = embeddedDir.resolve("src/main/resources/docroot");
        if (!Files.exists(docroot))
            throw new FileNotFoundException(docroot.toString());

        context.setBaseResourceAsPath(docroot);
        context.addFilter(TilesFilter.class, "/tiles/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(servlet), "");
        context.addServlet(DefaultServlet.class, "/");
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
        Path keystorePath = embeddedDir.resolve("src/main/resources/etc/keystore.p12").toAbsolutePath();
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

    public static class TilesFilter implements Filter
    {
        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException
        {
            // delay to make any sequential loads visible
            delay(100);

            HttpServletRequest request = (HttpServletRequest)req;
            HttpServletResponse response = (HttpServletResponse)res;
            response.setHeader("Cache-Control", "max-age=5");

            String uri = request.getRequestURI();

            int param = uri.indexOf(';');
            boolean prefetch = param > 0 && thinking.contains(uri.substring(param + 1));
            if (prefetch)
            {
                // If we are still generating the HTML, this must be an early hint, so send the color tile
                chain.doFilter(req, res);
            }
            else
            {
                // otherwise send the black and white tile
                request.getRequestDispatcher(uri.replace("tiles/tile", "tiles/bw_tile")).forward(request, response);
            }
        }
    }

    private static void delay(int millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }

    static Servlet servlet = new HttpServlet()
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
        {

            String tag = Integer.toHexString(random.nextInt());
            thinking.add(tag);
            try
            {
                for (int row = 0; row < 4; row++)
                {
                    for (int col = 0; col < 4; col++)
                        response.addHeader("Link", "<tiles/tile_%d_%d.png;%s>; rel=preload; as=image".formatted(row, col, tag));
                }
                response.sendError(103); // Jetty API until Servlet 6.2

                // delay to simulate computation
                delay(200);
            }
            finally
            {
                thinking.remove(tag);
            }

            // Write the response
            response.setStatus(200);
            response.setContentType("text/html");
            response.flushBuffer();
            ServletOutputStream out = response.getOutputStream();
            out.println("""
                    <!DOCTYPE html>
                    <html lang="en-US">
                    <head>
                    <style>
                    img {
                      width: 200px;
                      height: 200px;
                      border: 0;
                      display: block;
                    }
                    
                    td {
                      width: 200px;
                      height: 200px;
                    }
                    
                    tr {
                      height: 200px;
                    }
                    
                    table {
                      border-collapse: collapse;
                    }
                    
                    table, th, td {
                       border: 0 solid black;
                       margin: 0;
                       padding: 0;
                    }
                    </style>
                    </head>
                    
                    <body><div id="tiles"><table><tbody>
                    """);

            for (int row = 0; row < 4; row++)
            {
                out.println("<tr>");
                for (int col = 0; col < 4; col++)
                    out.println("<td><img src=\"tiles/tile_%d_%d.png;%s\" alt=\"\" /></td>".formatted(row, col, tag));
                out.println("</tr>");
            }

            out.println("</table></tbody></div></body></html>");
        }

    };
}
