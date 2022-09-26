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

package org.eclipse.jetty.test.client.transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HttpInterimResponseTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testImplicit100Continue(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                // Reading the request content immediately
                // issues an implicit 100 Continue response.
                Content.Source.consumeAll(request, callback);
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new StringRequestContent("request-content"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testMultipleDifferentInterimResponses(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                CompletableFuture<Void> completable = response.writeInterim(HttpStatus.CONTINUE_100, HttpFields.EMPTY)
                    .thenCompose(ignored ->
                    {
                        Callback.Completable c = new Callback.Completable();
                        Content.Source.consumeAll(request, c);
                        return c;
                    })
                    .thenCompose(ignored ->
                    {
                        HttpFields.Mutable fields1 = HttpFields.build();
                        fields1.put("Progress", "25%");
                        return response.writeInterim(HttpStatus.PROCESSING_102, fields1);
                    })
                    .thenCompose(ignored ->
                    {
                        HttpFields.Mutable fields2 = HttpFields.build();
                        fields2.put("Progress", "100%");
                        return response.writeInterim(HttpStatus.PROCESSING_102, fields2);
                    })
                    .thenRun(() ->
                    {
                        response.setStatus(200);
                        response.getHeaders().put("X-Header", "X-Value");
                    })
                    .thenCompose(ignored ->
                    {
                        HttpFields.Mutable hints = HttpFields.build();
                        hints.put(HttpHeader.LINK, "</main.css>; rel=preload");
                        return response.writeInterim(HttpStatus.EARLY_HINT_103, hints)
                            .thenApply(i -> hints);
                    })
                    .thenCompose(hints1 ->
                    {
                        HttpFields.Mutable hints = HttpFields.build();
                        hints.put(HttpHeader.LINK, "</style.css>; rel=preload");
                        return response.writeInterim(HttpStatus.EARLY_HINT_103, hints)
                            .thenApply(i -> HttpFields.build(hints1).add(hints));
                    })
                    .thenCompose(hints ->
                    {
                        response.getHeaders().put("X-Header-Foo", "Foo");
                        response.getHeaders().add(hints);
                        Callback.Completable c = new Callback.Completable();
                        Content.Sink.write(response, true, "response-content", c);
                        return c;
                    });
                callback.completeWith(completable);
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new StringRequestContent("request-content"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("X-Value", response.getHeaders().get("X-Header"));
        assertEquals("Foo", response.getHeaders().get("X-Header-Foo"));
        assertThat(response.getHeaders().getValuesList(HttpHeader.LINK), contains("</main.css>; rel=preload", "</style.css>; rel=preload"));
        assertEquals("response-content", response.getContentAsString());
    }
}
