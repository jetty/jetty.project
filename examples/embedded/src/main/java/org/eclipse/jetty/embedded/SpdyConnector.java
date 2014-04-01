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

package org.eclipse.jetty.embedded;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.spdy.server.NPNServerConnectionFactory;
import org.eclipse.jetty.spdy.server.SPDYServerConnectionFactory;
import org.eclipse.jetty.spdy.server.http.HTTPSPDYServerConnectionFactory;
import org.eclipse.jetty.spdy.server.http.ReferrerPushStrategy;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/* ------------------------------------------------------------ */
/**
 * A Jetty server with HTTP and SPDY connectors.
 */
public class SpdyConnector
{
    public static void main(String[] args) throws Exception
    {
        String jetty_home = System.getProperty("jetty.home","../../jetty-distribution/target/distribution");
        System.setProperty("jetty.home", jetty_home);

        // The Server
        Server server = new Server();

        // HTTP Configuration
        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setSecureScheme("https");
        http_config.setSecurePort(8443);

        // HTTP connector
        ServerConnector http = new ServerConnector(server,new HttpConnectionFactory(http_config));        
        http.setPort(8080);
        server.addConnector(http);
 
        // SSL Context Factory for HTTPS and SPDY
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(jetty_home + "/etc/keystore");
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");

        // HTTPS Configuration
        HttpConfiguration https_config = new HttpConfiguration(http_config);
        https_config.addCustomizer(new SecureRequestCustomizer());
        
        // SPDY versions
        HTTPSPDYServerConnectionFactory spdy2 = 
            new HTTPSPDYServerConnectionFactory(2,https_config);

        HTTPSPDYServerConnectionFactory spdy3 = 
            new HTTPSPDYServerConnectionFactory(3,https_config,new ReferrerPushStrategy());

        // NPN Factory
        SPDYServerConnectionFactory.checkProtocolNegotiationAvailable();
        NPNServerConnectionFactory npn = 
            new NPNServerConnectionFactory(spdy3.getProtocol(),spdy2.getProtocol(),http.getDefaultProtocol());
        npn.setDefaultProtocol(http.getDefaultProtocol());
        
        // SSL Factory
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory,npn.getProtocol());
        
        // SPDY Connector
        ServerConnector spdyConnector = 
            new ServerConnector(server,ssl,npn,spdy3,spdy2,new HttpConnectionFactory(https_config));
        spdyConnector.setPort(8443);
        server.addConnector(spdyConnector);
        
        // Set a handler
        server.setHandler(new HelloHandler());

        // Start the server
        server.start();
        server.join();
    }
}
