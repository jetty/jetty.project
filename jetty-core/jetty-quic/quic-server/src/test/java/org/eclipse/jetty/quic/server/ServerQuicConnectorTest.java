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

package org.eclipse.jetty.quic.server;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class ServerQuicConnectorTest
{
    @Disabled
    @Test
    public void testSmall() throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");

        Server server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setHttpCompliance(HttpCompliance.LEGACY); // enable HTTP/0.9
        HttpConnectionFactory connectionFactory = new HttpConnectionFactory(config);

        QuicServerConnector connector = new QuicServerConnector(server, sslContextFactory, connectionFactory);
        connector.setPort(8443);
        server.addConnector(connector);

        server.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.write(true, callback, """
                    <html>
                      <body>
                        Request served
                      </body>
                    </html>
                    """);
            }
        });

        server.start();

        System.out.println("Started.");
        System.in.read();

        server.stop();
    }

    @Disabled
    @Test
    public void testBig() throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");

        Server server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setHttpCompliance(HttpCompliance.LEGACY); // enable HTTP/0.9
        HttpConnectionFactory connectionFactory = new HttpConnectionFactory(config);

        QuicServerConnector connector = new QuicServerConnector(server, sslContextFactory, connectionFactory);
        connector.setPort(8443);
        server.addConnector(connector);

        server.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                int contentLength = 16 * 1024 * 1024;
                response.setContentLength(contentLength);
                response.setContentType("text/plain");
                response.write(true, callback, "0".repeat(contentLength));
            }
        });

        server.start();

        System.out.println("Started.");
        System.in.read();

        server.stop();
    }
}
