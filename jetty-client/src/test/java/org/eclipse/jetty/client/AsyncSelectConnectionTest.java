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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.ServerSocket;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.BeforeClass;

public class AsyncSelectConnectionTest extends AbstractConnectionTest
{
    protected HttpClient newHttpClient()
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.setConnectBlocking(false);
        return httpClient;
    }

    static SslContextFactory ctx = new SslContextFactory(MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath());

    @BeforeClass
    public static void initKS() throws Exception
    {
        ctx.setKeyStorePassword("storepwd");
        ctx.setKeyManagerPassword("keypwd");
        ctx.start();
    }

    @Override
    protected String getScheme()
    {
        return "https";
    }
    
    @Override
    protected ServerSocket newServerSocket() throws IOException
    {
        return ctx.newSslServerSocket(null,0,100);
    }

    @Override
    public void testServerHalfClosedIncomplete() throws Exception
    {
        // SSL doesn't do half closes
    }
    
    @Override
    public void testServerClosedIncomplete() throws Exception
    {
        super.testServerClosedIncomplete();
    }

    
}
