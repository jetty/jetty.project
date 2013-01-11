//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.spdy.server.NPNServerConnectionFactory;
import org.eclipse.jetty.spdy.server.http.HTTPSPDYServerConnectionFactory;
import org.eclipse.jetty.spdy.server.http.PushStrategy;
import org.eclipse.jetty.spdy.server.http.ReferrerPushStrategy;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.TimerScheduler;

/* ------------------------------------------------------------ */
/**
 * A Jetty server with multiple connectors.
 *
 */
public class ManyConnectors
{
    public static void main(String[] args) throws Exception
    {
        String jetty_home = System.getProperty("jetty.home","../jetty-server/src/main/config");
        System.setProperty("jetty.home", jetty_home);

        Server server = new Server();

        // HTTP connector
        ServerConnector connector0 = new ServerConnector(server);
        connector0.setPort(8080);
        connector0.setIdleTimeout(30000);

        // HTTPS connector
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(jetty_home + "/etc/keystore");
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");

        ServerConnector connector1 = new ServerConnector(server,sslContextFactory);
        connector1.setPort(8443);


        // A verbosely fully configured connector with SSL, SPDY and HTTP

        HttpConfiguration config = new HttpConfiguration();
        config.setSecureScheme("https");
        config.setSecurePort(8443);
        config.setOutputBufferSize(32768);
        config.setRequestHeaderSize(8192);
        config.setResponseHeaderSize(8192);
        config.addCustomizer(new ForwardedRequestCustomizer());
        config.addCustomizer(new SecureRequestCustomizer());

        HttpConnectionFactory http = new HttpConnectionFactory(config);
        http.setInputBufferSize(16384);

        PushStrategy push = new ReferrerPushStrategy();
        HTTPSPDYServerConnectionFactory spdy2 = new HTTPSPDYServerConnectionFactory(2,config,push);
        spdy2.setInputBufferSize(8192);
        spdy2.setInitialWindowSize(32768);

        HTTPSPDYServerConnectionFactory spdy3 = new HTTPSPDYServerConnectionFactory(3,config,push);
        spdy2.setInputBufferSize(8192);

        NPNServerConnectionFactory npn = new NPNServerConnectionFactory(spdy3.getProtocol(),spdy2.getProtocol(),http.getProtocol());
        npn.setDefaultProtocol(http.getProtocol());
        npn.setInputBufferSize(1024);

        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory,npn.getProtocol());

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(256);
        TimerScheduler scheduler = new TimerScheduler();
        ByteBufferPool bufferPool= new ArrayByteBufferPool(32,4096,32768);

        ServerConnector connector2 = new ServerConnector(server,threadPool,scheduler,bufferPool,2,2,ssl,npn,spdy3,spdy2,http);
        connector2.setDefaultProtocol("ssl-npn");
        connector2.setPort(8444);
        connector2.setIdleTimeout(30000);
        connector2.setSoLingerTime(10000);

        // Set the connectors
        server.setConnectors(new Connector[] { connector0, connector1, connector2 });


        server.setHandler(new HelloHandler());

        server.start();
        server.dumpStdErr();
        server.join();
    }
}
