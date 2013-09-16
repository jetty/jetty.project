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

import java.io.FileInputStream;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

public class ExternalKeyStoreAsyncSslServerAndClientCreator extends AbstractSslServerAndClientCreator implements ServerAndClientCreator
{

    public HttpClient createClient(long idleTimeout, long timeout, int connectTimeout) throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.setMaxConnectionsPerAddress(2);

        String keystore = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();

        httpClient.setKeyStoreInputStream(new FileInputStream(keystore));
        httpClient.setKeyStorePassword("storepwd");
        httpClient.setKeyManagerPassword("keypwd");
        httpClient.start();
        return httpClient;
    }


}
