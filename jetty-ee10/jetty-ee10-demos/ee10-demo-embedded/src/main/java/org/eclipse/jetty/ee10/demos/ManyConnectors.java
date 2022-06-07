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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * A Jetty server with multiple connectors.
 */
public class ManyConnectors
{
    public static Server createServer(int plainPort, int securePort) throws Exception
    {
        // Since this example shows off SSL configuration, we need a keystore
        // with the appropriate key.
        Path keystorePath = Paths.get("src/main/resources/etc/keystore.p12").toAbsolutePath();
        if (!Files.exists(keystorePath))
            throw new FileNotFoundException(keystorePath.toString());

        // Create a basic jetty server object without declaring the port. Since
        // we are configuring connectors directly we'll be setting ports on
        // those connectors.
        Server server = new Server();

        // HTTP Configuration
        // HttpConfiguration is a collection of configuration information
        // appropriate for http and https. The default scheme for http is
        // <code>http</code> of course, as the default for secured http is
        // <code>https</code> but we show setting the scheme to show it can be
        // done. The port for secured communication is also set here.
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(securePort);
        httpConfig.setOutputBufferSize(32768);

        // HTTP connector
        // The first server connector we create is the one for http, passing in
        // the http configuration we configured above so it can get things like
        // the output buffer size, etc. We also set the port (8080) and
        // configure an idle timeout.
        ServerConnector http = new ServerConnector(server,
            new HttpConnectionFactory(httpConfig));
        http.setPort(plainPort);
        http.setIdleTimeout(30000);

        // SSL Context Factory for HTTPS
        // SSL requires a certificate so we configure a factory for ssl contents
        // with information pointing to what keystore the ssl connection needs
        // to know about. Much more configuration is available the ssl context,
        // including things like choosing the particular certificate out of a
        // keystore to be used.

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath.toString());
        sslContextFactory.setKeyStorePassword("storepwd");

        // OPTIONAL: Un-comment the following to use Conscrypt for SSL instead of
        // the native JSSE implementation.

        //Security.addProvider(new OpenSSLProvider());
        //sslContextFactory.setProvider("Conscrypt");

        // HTTPS Configuration
        // A new HttpConfiguration object is needed for the next connector and
        // you can pass the old one as an argument to effectively clone the
        // contents. On this HttpConfiguration object we add a
        // SecureRequestCustomizer which is how a new connector is able to
        // resolve the https connection before handing control over to the Jetty
        // Server.
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        SecureRequestCustomizer src = new SecureRequestCustomizer();
        src.setStsMaxAge(2000);
        src.setStsIncludeSubDomains(true);
        httpsConfig.addCustomizer(src);

        // HTTPS connector
        // We create a second ServerConnector, passing in the http configuration
        // we just made along with the previously created ssl context factory.
        // Next we set the port and a longer idle timeout.
        ServerConnector https = new ServerConnector(server,
            new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory(httpsConfig));
        https.setPort(securePort);
        https.setIdleTimeout(500000);

        // Here you see the server having multiple connectors registered with
        // it, now requests can flow into the server from both http and https
        // urls to their respective ports and be processed accordingly by jetty.
        // A simple handler is also registered with the server so the example
        // has something to pass requests off to.

        // Set the connectors
        server.setConnectors(new Connector[]{http, https});

        // Set a handler
        server.setHandler(new HelloHandler());
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        int securePort = ExampleUtil.getPort(args, "jetty.https.port", 8443);
        Server server = createServer(port, securePort);
        // Start the server
        server.start();
        server.dumpStdErr();
        server.join();
    }
}
