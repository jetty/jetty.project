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

package org.eclipse.jetty.http2.tests;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

public class ContinuationTest extends AbstractTest
{
    @Test
    public void testRequestContinuationFramesWithUnlimitedMaxRequestHeadersSize() throws Exception
    {
        int maxHeaderBlockSize = 128;
        String headerName = "Large-Header";
        // Configure the server with unlimited request headers size.
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setRequestHeaderSize(-1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertThat(request.getHeaders().get(headerName), notNullValue());
                callback.succeeded();
                return true;
            }
        }, httpConfig);
        // Configure a small max header block size, so the client sends CONTINUATION frames.
        http2Client.setMaxHeaderBlockFragment(maxHeaderBlockSize);

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .headers(h -> h.put(headerName, "X".repeat(2 * maxHeaderBlockSize)))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
    }

    @Test
    public void testResponseContinuationFramesWithUnlimitedMaxResponseHeadersSize() throws Exception
    {
        int maxHeaderBlockSize = 128;
        String headerName = "Large-Header";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(headerName, "X".repeat(2 * maxHeaderBlockSize));
                callback.succeeded();
                return true;
            }
        });
        // Configure a small max header block size, so the server sends CONTINUATION frames.
        connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).setMaxHeaderBlockFragment(maxHeaderBlockSize);
        // Configure the client with unlimited response headers size.
        httpClient.setMaxResponseHeadersSize(-1);

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getHeaders().get(headerName), notNullValue());
    }
}
