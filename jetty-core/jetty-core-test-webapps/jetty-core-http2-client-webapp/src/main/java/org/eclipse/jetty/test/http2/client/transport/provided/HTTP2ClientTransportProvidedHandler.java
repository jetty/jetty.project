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

package org.eclipse.jetty.test.http2.client.transport.provided;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

public class HTTP2ClientTransportProvidedHandler extends Handler.Abstract
{
    private final HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client()));

    @Override
    protected void doStart() throws Exception
    {
        addBean(httpClient);
        super.doStart();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        // Verify that the HTTP2Client dependencies are provided by the server
        // by making a request to an external server, as if this Handler was a proxy.

        httpClient.newRequest("https://webtide.com/")
            .timeout(15, TimeUnit.SECONDS)
            .send(result ->
            {
                if (result.isSucceeded())
                {
                    response.setStatus(result.getResponse().getStatus());
                    callback.succeeded();
                }
                else
                {
                    callback.failed(result.getFailure());
                }
            });
        return true;
    }
}
