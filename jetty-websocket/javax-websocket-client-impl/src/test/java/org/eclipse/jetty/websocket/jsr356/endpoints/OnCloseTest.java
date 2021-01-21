//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.endpoints;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrEvents;
import org.eclipse.jetty.websocket.jsr356.client.AnnotatedClientEndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseEndpointConfigSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseReasonSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseSessionReasonSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseSocket;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class OnCloseTest
{
    public static Stream<Arguments> closeCases()
    {
        return Stream.of(
            Arguments.of(CloseSocket.class, "onClose()"),
            Arguments.of(CloseReasonSocket.class, "onClose(CloseReason)"),
            Arguments.of(CloseSessionSocket.class, "onClose(Session)"),
            Arguments.of(CloseReasonSessionSocket.class, "onClose(CloseReason,Session)"),
            Arguments.of(CloseSessionReasonSocket.class, "onClose(Session,CloseReason)"),
            Arguments.of(CloseEndpointConfigSocket.class, "onClose(EndpointConfig)")
        );
    }

    private static ClientContainer container = new ClientContainer();

    @ParameterizedTest
    @MethodSource("closeCases")
    public void testOnCloseCall(Class<?> closeClass, String expectedCloseEvent) throws Exception
    {
        // Scan annotations
        AnnotatedClientEndpointMetadata metadata = new AnnotatedClientEndpointMetadata(container, closeClass);
        AnnotatedEndpointScanner<ClientEndpoint, ClientEndpointConfig> scanner = new AnnotatedEndpointScanner<>(metadata);
        scanner.scan();

        // Build up EventDriver
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        ClientEndpointConfig config = metadata.getConfig();
        TrackingSocket endpoint = (TrackingSocket)closeClass.getDeclaredConstructor().newInstance();
        EndpointInstance ei = new EndpointInstance(endpoint, config, metadata);
        JsrEvents<ClientEndpoint, ClientEndpointConfig> jsrevents = new JsrEvents<>(metadata);

        EventDriver driver = new JsrAnnotatedEventDriver(policy, ei, jsrevents);

        // Execute onClose call
        driver.onClose(new CloseInfo(StatusCode.NORMAL, "normal"));

        // Test captured event
        LinkedBlockingQueue<String> events = endpoint.eventQueue;
        String closeEvent = events.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
        assertThat("Close Event", closeEvent, is(expectedCloseEvent));
    }
}
