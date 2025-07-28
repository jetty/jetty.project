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

package org.eclipse.jetty.http3.tests;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.RetryableRequestException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetryRequestTest extends AbstractClientServerTest
{
    @Test
    public void testRetryRequest() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.respond(new HeadersFrame(response, true));
                return null;
            }
        });

        AtomicReference<Result> resultRef = new AtomicReference<>();
        httpClient.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .path("/one")
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                // No HTTP/3 frames are processed until returning from this method.
                serverSessionRef.get().goAway(false);
                // Send a second request, should be failed by the client.
                httpClient.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .path("/two")
                    .timeout(5, TimeUnit.SECONDS)
                    .send(resultRef::set);
            });

        Result result = await().atMost(5, TimeUnit.SECONDS).until(resultRef::get, Objects::nonNull);

        assertTrue(result.isFailed());
        assertThat(result.getFailure(), instanceOf(RetryableRequestException.class));
    }
}
