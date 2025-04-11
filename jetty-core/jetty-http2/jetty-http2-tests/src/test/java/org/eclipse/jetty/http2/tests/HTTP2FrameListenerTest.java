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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class HTTP2FrameListenerTest extends AbstractTest
{
    @Test
    public void testFrameListener() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, callback);
                return true;
            }
        });
        // Set up the frame listener on the server.
        ListFrameListener serverFrameListener = new ListFrameListener();
        connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).addBean(serverFrameListener);

        // Set up the frame listener on the client.
        ListFrameListener clientFrameListener = new ListFrameListener();
        http2Client.addBean(clientFrameListener);

        String content = "data";
        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .body(new StringRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is(content));

        assertThat(serverFrameListener.incoming, not(empty()));
        assertThat(clientFrameListener.incoming, not(empty()));

        Function<List<Frame>, List<FrameType>> toTypeList = frames -> frames.stream().map(Frame::getType).toList();
        List<FrameType> clientOutgoingTypes = toTypeList.apply(clientFrameListener.outgoing);
        List<FrameType> clientIncomingTypes = toTypeList.apply(clientFrameListener.incoming);
        List<FrameType> serverOutgoingTypes = toTypeList.apply(serverFrameListener.outgoing);
        List<FrameType> serverIncomingTypes = toTypeList.apply(serverFrameListener.incoming);

        // Verify that what one side sent was what the other side received.
        assertThat(clientOutgoingTypes, equalTo(serverIncomingTypes));
        assertThat(serverOutgoingTypes, equalTo(clientIncomingTypes));

        // First frame sent must be a SETTINGS.
        assertThat(clientOutgoingTypes.get(0), is(FrameType.SETTINGS));
        assertThat(serverOutgoingTypes.get(0), is(FrameType.SETTINGS));

        // There must be at least one HEADERS and one DATA.
        assertThat(clientOutgoingTypes, containsInRelativeOrder(FrameType.HEADERS, FrameType.DATA));
        assertThat(serverOutgoingTypes, containsInRelativeOrder(FrameType.HEADERS, FrameType.DATA));
    }

    private static class ListFrameListener implements HTTP2Session.FrameListener
    {
        private final List<Frame> incoming = new ArrayList<>();
        private final List<Frame> outgoing = new ArrayList<>();

        @Override
        public void onIncomingFrame(Session session, Frame frame)
        {
            incoming.add(frame);
        }

        @Override
        public void onOutgoingFrame(Session session, Frame frame)
        {
            outgoing.add(frame);
        }
    }
}
