// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================


package org.eclipse.jetty.client.helperClasses;

import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 */
public abstract class AbstractSslServerAndClientCreator implements ServerAndClientCreator
{
    private static final Logger LOG = Log.getLogger(AbstractSslServerAndClientCreator.class);

    /* ------------------------------------------------------------ */
    public Server createServer() throws Exception
    {
        Server server = new Server();
        // SslSelectChannelConnector connector = new SslSelectChannelConnector();
        SslSocketConnector connector = new SslSocketConnector();

        String keystore = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();

        connector.setPort(0);
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStore(keystore);
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
        connector.setAllowRenegotiate(true);

        server.setConnectors(new Connector[]{ connector });
        server.setHandler(new GenericServerHandler());
        server.start();
        return server;
    }


}
