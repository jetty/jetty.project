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

package embedded.client.http;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HTTPClientDocs
{
    public void start() throws Exception
    {
        // tag::start[]
        // Instantiate HttpClient.
        HttpClient httpClient = new HttpClient();

        // Configure HttpClient, for example:
        httpClient.setFollowRedirects(false);

        // Start HttpClient.
        httpClient.start();
        // end::start[]
    }

    public void stop() throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.start();
        // tag::stop[]
        // Stop HttpClient.
        httpClient.stop();
        // end::stop[]
    }

    public void tlsExplicit() throws Exception
    {
        // tag::tlsExplicit[]
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);

        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));
        httpClient.start();
        // end::tlsExplicit[]
    }

    public void tlsNoValidation() throws Exception
    {
        // tag::tlsNoValidation[]
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        // Disable certificate validation at the TLS level.
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        // end::tlsNoValidation[]
    }

    public void tlsAppValidation() throws Exception
    {
        // tag::tlsAppValidation[]
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        // Only allow subdomains of domain.com.
        sslContextFactory.setHostnameVerifier((hostName, session) -> hostName.endsWith(".domain.com"));
        // end::tlsAppValidation[]
    }
}
