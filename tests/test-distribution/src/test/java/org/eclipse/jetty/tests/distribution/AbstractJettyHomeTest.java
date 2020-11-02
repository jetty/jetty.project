//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.tests.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;

public class AbstractJettyHomeTest
{
    protected HttpClient client;

    protected void startHttpClient() throws Exception
    {
        startHttpClient(false);
    }

    protected void startHttpClient(boolean secure) throws Exception
    {
        if (secure)
        {
            SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
            ClientConnector clientConnector = new ClientConnector();
            clientConnector.setSslContextFactory(sslContextFactory);
            HttpClientTransportOverHTTP httpClientTransportOverHTTP = new HttpClientTransportOverHTTP(clientConnector);
            startHttpClient(() -> new HttpClient(httpClientTransportOverHTTP));
        }
        else
            startHttpClient(HttpClient::new);
    }

    protected void startHttpClient(Supplier<HttpClient> supplier) throws Exception
    {
        client = supplier.get();
        client.start();
    }

    public static Path newTestJettyBaseDirectory() throws IOException
    {
        Path bases = MavenTestingUtils.getTargetTestingPath("bases");
        FS.ensureDirExists(bases);
        return Files.createTempDirectory(bases, "jetty_base_");
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
    }
}
