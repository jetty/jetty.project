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

package org.eclipse.jetty.client.helperClasses;

import org.eclipse.jetty.client.HttpClient;

public class SslServerAndClientCreator extends AbstractSslServerAndClientCreator implements ServerAndClientCreator
{

    public HttpClient createClient(long idleTimeout, long timeout, int connectTimeout) throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setIdleTimeout(idleTimeout);
        httpClient.setTimeout(timeout);
        httpClient.setConnectTimeout(connectTimeout);
        httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        httpClient.setMaxConnectionsPerAddress(2);
        httpClient.start();
        return httpClient;
    }
}
