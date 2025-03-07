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

package org.eclipse.jetty.test.client.transport;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RequestNanoTimesTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestNanoTimes(TransportType transportType) throws Exception
    {
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put("X-Request-BeginNanoTime", request.getBeginNanoTime());
                response.getHeaders().put("X-Request-HeadersNanoTime", request.getHeadersNanoTime());
                callback.succeeded();
                return true;
            }
        });

        for (int i = 0; i < 2; ++i)
        {
            long clientRequestNanoTime = NanoTime.now();
            ContentResponse response = client.newRequest(newURI(transportType))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
            long serverRequestBeginNanoTime = response.getHeaders().getLongField("X-Request-BeginNanoTime");
            long serverRequestHeadersNanoTime = response.getHeaders().getLongField("X-Request-HeadersNanoTime");

            String reason = "request " + i;
            assertThat(reason, NanoTime.elapsed(clientRequestNanoTime, serverRequestBeginNanoTime), greaterThan(0L));
            assertThat(reason, NanoTime.elapsed(serverRequestBeginNanoTime, serverRequestHeadersNanoTime), greaterThanOrEqualTo(0L));
        }
    }
}
