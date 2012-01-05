// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import org.eclipse.jetty.client.helperClasses.ExternalKeyStoreAsyncSslServerAndClientCreator;
import org.eclipse.jetty.client.helperClasses.ServerAndClientCreator;
import org.junit.Before;

public class ExternalKeyStoreAsyncSslHttpExchangeTest extends SslHttpExchangeTest
{
    private static ServerAndClientCreator serverAndClientCreator = new ExternalKeyStoreAsyncSslServerAndClientCreator();
    
    @Before
    public void setUpOnce() throws Exception
    {
        _scheme="https";
        _server = serverAndClientCreator.createServer();
        _httpClient = serverAndClientCreator.createClient(3000L,3500L,2000);
        _port = _server.getConnectors()[0].getLocalPort();
    }
}
