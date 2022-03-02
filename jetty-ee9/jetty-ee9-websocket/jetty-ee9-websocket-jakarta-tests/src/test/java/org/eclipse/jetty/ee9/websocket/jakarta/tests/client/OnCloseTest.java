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

package org.eclipse.jetty.websocket.jakarta.tests.client;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.websocket.ClientEndpointConfig;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.jakarta.client.internal.BasicClientEndpointConfig;
import org.eclipse.jetty.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketFrameHandler;
import org.eclipse.jetty.websocket.jakarta.common.UpgradeRequest;
import org.eclipse.jetty.websocket.jakarta.common.UpgradeRequestAdapter;
import org.eclipse.jetty.websocket.jakarta.tests.WSEventTracker;
import org.eclipse.jetty.websocket.jakarta.tests.client.samples.CloseReasonSessionSocket;
import org.eclipse.jetty.websocket.jakarta.tests.client.samples.CloseReasonSocket;
import org.eclipse.jetty.websocket.jakarta.tests.client.samples.CloseSessionReasonSocket;
import org.eclipse.jetty.websocket.jakarta.tests.client.samples.CloseSessionSocket;
import org.eclipse.jetty.websocket.jakarta.tests.client.samples.CloseSocket;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;

public class OnCloseTest
{
    private static class Case
    {
        public static Case add(List<Case[]> data, Class<?> closeClass)
        {
            Case tcase = new Case(closeClass);
            data.add(new Case[]{tcase});
            return tcase;
        }

        public Case(Class<?> closeClass)
        {
            this.closeClass = closeClass;
        }

        Class<?> closeClass;
        String expectedCloseEvent;

        public Case expect(String expectedEvent)
        {
            this.expectedCloseEvent = expectedEvent;
            return this;
        }

        @Override
        public String toString()
        {
            return closeClass.getSimpleName();
        }
    }

    private static JakartaWebSocketClientContainer container = new JakartaWebSocketClientContainer();

    public static Stream<Arguments> data() throws Exception
    {
        return Stream.of(
            Arguments.of(new Case(CloseSocket.class).expect("onClose()")),
            Arguments.of(new Case(CloseReasonSocket.class).expect("onClose(CloseReason)")),
            Arguments.of(new Case(CloseSessionSocket.class).expect("onClose(Session)")),
            Arguments.of(new Case(CloseReasonSessionSocket.class).expect("onClose(CloseReason,Session)")),
            Arguments.of(new Case(CloseSessionReasonSocket.class).expect("onClose(Session,CloseReason)"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("data")
    public void testOnCloseCall(Case testcase) throws Exception
    {
        WSEventTracker endpoint = (WSEventTracker)testcase.closeClass.getConstructor().newInstance();

        ClientEndpointConfig config = new BasicClientEndpointConfig();
        // TODO: use ConfiguredEndpoint here?

        JakartaWebSocketClientContainer container = new JakartaWebSocketClientContainer();
        container.start();

        UpgradeRequest request = new UpgradeRequestAdapter();
        JakartaWebSocketFrameHandler frameHandler = container.newFrameHandler(endpoint, request);
        frameHandler.onOpen(new CoreSession.Empty(), Callback.NOOP);

        // Execute onClose call
        frameHandler.onFrame(CloseStatus.toFrame(CloseStatus.NORMAL), Callback.NOOP);
        frameHandler.onClosed(CloseStatus.NORMAL_STATUS, Callback.NOOP);

        // Test captured event
        BlockingQueue<String> events = endpoint.events;
        assertThat("Number of Events Captured", events.size(), Matchers.is(1));
        String closeEvent = events.poll(1, TimeUnit.SECONDS);
        assertThat("Close Event", closeEvent, Matchers.is(testcase.expectedCloseEvent));
    }
}
