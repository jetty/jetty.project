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

package org.eclipse.jetty.test.rfcs;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.test.support.TestableJettyServer;
import org.eclipse.jetty.test.support.rawhttp.HttpSocket;
import org.eclipse.jetty.test.support.rawhttp.HttpsSocketImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;

/**
 * Perform the RFC2616 tests against a server running with the Jetty NIO Connector and listening on HTTPS (HTTP over SSL).
 * TODO
 */
@Disabled("TODO")
public class RFC2616NIOHttpsTest extends RFC2616BaseTest
{
    @BeforeAll
    public static void setupServer() throws Exception
    {
        TestableJettyServer server = new TestableJettyServer();
        server.setScheme(HttpScheme.HTTPS.asString());
        server.addConfiguration("RFC2616Base.xml");
        server.addConfiguration("RFC2616_Redirects.xml");
        server.addConfiguration("RFC2616_Filters.xml");
        server.addConfiguration("NIOHttps.xml");
        setUpServer(server, RFC2616NIOHttpsTest.class);
    }

    @Override
    public HttpSocket getHttpClientSocket() throws Exception
    {
        return new HttpsSocketImpl();
    }
}
