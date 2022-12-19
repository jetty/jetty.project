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

package org.eclipse.jetty.http2.tests;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContentLengthTest extends AbstractTest
{
    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT"})
    public void testZeroContentLengthAddedByServer(String method) throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .method(method)
            .send();

        HttpFields responseHeaders = response.getHeaders();
        long contentLength = responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH.asString());
        assertEquals(0, contentLength);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT"})
    public void testContentLengthAddedByServer(String method) throws Exception
    {
        byte[] data = new byte[512];
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .method(method)
            .send();

        HttpFields responseHeaders = response.getHeaders();
        long contentLength = responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH.asString());
        assertEquals(data.length, contentLength);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT"})
    public void testClientContentLengthMismatch(String method) throws Exception
    {
        byte[] data = new byte[512];
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                Content.Source.consumeAll(request, callback);
                return true;
            }
        });

        Session clientSession = newClientSession(new Session.Listener() {});
        CountDownLatch resetLatch = new CountDownLatch(1);
        // Set a wrong Content-Length header.
        HttpFields requestHeaders = HttpFields.build().put(HttpHeader.CONTENT_LENGTH, String.valueOf(data.length + 1));
        clientSession.newStream(new HeadersFrame(newRequest(method, requestHeaders), null, false), new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                resetLatch.countDown();
                callback.succeeded();
            }
        }).thenAccept(stream -> stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(data), true)));

        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT"})
    public void testGzippedContentLengthAddedByServer(String method) throws Exception
    {
        byte[] data = new byte[4096];

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMethods(method);
        gzipHandler.setMinGzipSize(data.length / 2);
        gzipHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                // Write a single buffer, with a Content-Length
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, data.length);
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        start(gzipHandler);

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .method(method)
            .send();

        if (!HttpMethod.HEAD.is(method))
            assertThat(response.getContent().length, is(data.length));
    }
}
