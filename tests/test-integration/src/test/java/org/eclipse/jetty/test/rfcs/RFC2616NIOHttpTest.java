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

package org.eclipse.jetty.test.rfcs;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.test.support.XmlBasedJettyServer;
import org.eclipse.jetty.test.support.rawhttp.HttpSocket;
import org.eclipse.jetty.test.support.rawhttp.HttpSocketImpl;
import org.junit.jupiter.api.BeforeAll;

/**
 * Perform the RFC2616 tests against a server running with the Jetty NIO Connector and listening on standard HTTP.
 */
public class RFC2616NIOHttpTest extends RFC2616BaseTest
{
    @BeforeAll
    public static void setupServer() throws Exception
    {
        XmlBasedJettyServer server = new XmlBasedJettyServer();
        server.setScheme(HttpScheme.HTTP.asString());
        server.addXmlConfiguration("RFC2616Base.xml");
        server.addXmlConfiguration("RFC2616_Redirects.xml");
        server.addXmlConfiguration("RFC2616_Filters.xml");
        server.addXmlConfiguration("NIOHttp.xml");
        setUpServer(server, RFC2616NIOHttpTest.class);
    }

    @Override
    public HttpSocket getHttpClientSocket()
    {
        return new HttpSocketImpl();
    }
}
