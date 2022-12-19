//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.proxy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InterimResponseProxyTest extends AbstractProxyTest
{
    @Test
    public void testInterimResponses() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                CompletableFuture<Void> completable = response.writeInterim(HttpStatus.CONTINUE_100, HttpFields.EMPTY)
                    .thenCompose(ignored -> Promise.Completable.<String>with(p -> Content.Source.asString(request, StandardCharsets.UTF_8, p)))
                    .thenCompose(content -> response.writeInterim(HttpStatus.PROCESSING_102, HttpFields.EMPTY).thenApply(ignored -> content))
                    .thenCompose(content -> response.writeInterim(HttpStatus.EARLY_HINT_103, HttpFields.EMPTY).thenApply(ignored -> content))
                    .thenCompose(content -> Callback.Completable.with(c -> Content.Sink.write(response, true, content, c)));
                callback.completeWith(completable);
                return true;
            }
        });

        startProxy(new ProxyHandler.Reverse(clientToProxyRequest ->
            HttpURI.build(clientToProxyRequest.getHttpURI()).port(serverConnector.getLocalPort())));

        startClient();

        String content = "hello world";
        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort())
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new StringRequestContent(content))
            .send();
        assertEquals(content, response.getContentAsString());
    }
}
