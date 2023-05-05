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

package org.eclipse.jetty.ee10.test.rfcs;

import java.nio.file.Path;

import org.eclipse.jetty.ee10.test.support.XmlBasedJettyServer;
import org.eclipse.jetty.ee10.test.support.rawhttp.HttpSocket;
import org.eclipse.jetty.ee10.test.support.rawhttp.HttpsSocketImpl;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Perform the RFC2616 tests against a server running with the Jetty NIO Connector and listening on HTTPS (HTTP over SSL).
 */
@ExtendWith(WorkDirExtension.class)
public class RFC2616NIOHttpsTest extends RFC2616BaseTest
{
    private static XmlBasedJettyServer xmlBasedJettyServer;

    @BeforeAll
    public static void setupServer(WorkDir workDir) throws Exception
    {
        Path tmpPath = workDir.getEmptyPathDir();
        XmlBasedJettyServer server = new XmlBasedJettyServer();
        server.setScheme(HttpScheme.HTTPS.asString());
        server.addXmlConfiguration("RFC2616Base.xml");
        server.addXmlConfiguration("RFC2616_Redirects.xml");
        server.addXmlConfiguration("RFC2616_Filters.xml");
        server.addXmlConfiguration("ssl.xml");
        server.addXmlConfiguration("NIOHttps.xml");
        xmlBasedJettyServer = setUpServer(server, RFC2616NIOHttpsTest.class, tmpPath);
    }

    @Override
    public HttpSocket getHttpClientSocket() throws Exception
    {
        return new HttpsSocketImpl();
    }

    @AfterAll
    public static void tearDownServer() throws Exception
    {
        xmlBasedJettyServer.stop();
    }

    @Override
    public XmlBasedJettyServer getServer()
    {
        return xmlBasedJettyServer;
    }
}
