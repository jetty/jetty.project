// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.test.rfcs;

import java.io.IOException;

import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.test.support.TestableJettyServer;
import org.eclipse.jetty.test.support.rawhttp.HttpSocket;
import org.eclipse.jetty.test.support.rawhttp.HttpsSocketImpl;

/**
 * Perform the RFC2616 tests against a server running with the Jetty BIO Connector and listening on HTTPS (HTTP over SSL).
 */
public class RFC2616BIOHttpsTest extends RFC2616BaseTest
{
    @Override
    public TestableJettyServer getJettyServer() throws IOException
    {
        TestableJettyServer server = new TestableJettyServer();
        server.setScheme(HttpSchemes.HTTPS);
        server.addConfiguration("RFC2616Base.xml");
        server.addConfiguration("RFC2616_Redirects.xml");
        server.addConfiguration("RFC2616_Filters.xml");
        server.addConfiguration("BIOHttps.xml");
        return server;
    }

    @Override
    public HttpSocket getHttpClientSocket() throws Exception
    {
        return new HttpsSocketImpl();
    }
}
