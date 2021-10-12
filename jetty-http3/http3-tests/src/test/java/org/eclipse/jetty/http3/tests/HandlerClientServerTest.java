//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HandlerClientServerTest extends AbstractClientServerTest
{
    @Test
    public void test() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);
                serverLatch.countDown();
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HeadersFrame frame = new HeadersFrame(newRequest("/"), true);
        session.newRequest(frame, new Stream.Listener()
            {
                @Override
                public void onResponse(Stream stream, HeadersFrame frame)
                {
                    MetaData.Response response = (MetaData.Response)frame.getMetaData();
                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                    clientResponseLatch.countDown();
                }
            })
            .get(5, TimeUnit.SECONDS);

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
    }
}
