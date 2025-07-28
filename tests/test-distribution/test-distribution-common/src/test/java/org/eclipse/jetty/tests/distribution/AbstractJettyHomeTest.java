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

package org.eclipse.jetty.tests.distribution;

import java.nio.file.Path;
import java.util.function.Supplier;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.tests.testers.ProcessWrapper;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({WorkDirExtension.class})
public class AbstractJettyHomeTest
{
    protected HttpClient client;

    public static final int START_TIMEOUT = ProcessWrapper.START_TIMEOUT;

    public static String toEnvironment(String module, String environment)
    {
        return environment + "-" + module;
    }
    
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
            clientConnector.setSelectors(1);
            clientConnector.setSslContextFactory(sslContextFactory);
            HttpClientTransportOverHTTP httpClientTransportOverHTTP = new HttpClientTransportOverHTTP(clientConnector);
            startHttpClient(() -> new HttpClient(httpClientTransportOverHTTP));
        }
        else
        {
            startHttpClient(HttpClient::new);
        }
    }

    protected void startHttpClient(Supplier<HttpClient> supplier) throws Exception
    {
        client = supplier.get();
        client.start();
    }

    public WorkDir workDir;

    public Path newTestJettyBaseDirectory()
    {
        return workDir.getEmptyPathDir();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
    }

    protected static class ResponseDetails implements Supplier<String>
    {
        private final ContentResponse response;

        public ResponseDetails(ContentResponse response)
        {
            this.response = response;
        }

        @Override
        public String get()
        {
            StringBuilder ret = new StringBuilder();
            ret.append(response.toString()).append(System.lineSeparator());
            ret.append(response.getHeaders().toString()).append(System.lineSeparator());
            ret.append(response.getContentAsString()).append(System.lineSeparator());
            return ret.toString();
        }
    }
}
