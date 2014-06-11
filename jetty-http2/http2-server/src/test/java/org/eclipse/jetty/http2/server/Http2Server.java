//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.http2.server;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.hpack.HpackContext;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.spdy.server.NPNServerConnectionFactory;
import org.eclipse.jetty.spdy.server.SPDYServerConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;


/* ------------------------------------------------------------ */
/**
 */
public class Http2Server
{
    public static void main(String... args) throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(server, "/");
        context.setResourceBase("/tmp");
        context.addServlet(new ServletHolder(servlet), "/test/*");
        context.addServlet(DefaultServlet.class, "/");
        server.setHandler(context);


        // HTTP Configuration
        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setSecureScheme("https");
        http_config.setSecurePort(8443);

        // HTTP connector
        ServerConnector http = new ServerConnector(server,new HttpConnectionFactory(http_config));        
        http.setPort(8080);
        server.addConnector(http);
 
        // SSL Context Factory for HTTPS and SPDY
        String jetty_distro = System.getProperty("jetty.distro","../../jetty-distribution/target/distribution");
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(jetty_distro + "/etc/keystore");
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");

        // HTTPS Configuration
        HttpConfiguration https_config = new HttpConfiguration(http_config);
        https_config.addCustomizer(new SecureRequestCustomizer());
        
        
        // HTTP2 factory
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(https_config);
        
        SPDYServerConnectionFactory.checkProtocolNegotiationAvailable();
        NPNServerConnectionFactory npn = 
            new NPNServerConnectionFactory(h2.getProtocol(),http.getDefaultProtocol());
        npn.setDefaultProtocol(http.getDefaultProtocol());
        
        // SSL Factory
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory,npn.getProtocol());
        
        // SPDY Connector
        ServerConnector http2Connector = 
            new ServerConnector(server,ssl,npn,h2,new HttpConnectionFactory(https_config));
        http2Connector.setPort(8443);
        server.addConnector(http2Connector);
        
        NextProtoNego.debug=true;
        
        server.start();
        server.dumpStdErr();
        server.join();
    }
    
    
    static Servlet servlet = new HttpServlet()
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setHeader("custom","value");
            response.setContentType("text/plain");
            String content = "Hello from Jetty HTTP2\n";
            response.setContentLength(content.length());
            response.getOutputStream().print(content);
        }
    };
}
