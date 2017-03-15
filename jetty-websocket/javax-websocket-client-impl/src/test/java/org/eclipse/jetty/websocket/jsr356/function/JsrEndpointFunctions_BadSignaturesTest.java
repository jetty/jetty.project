//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.function;

import static org.hamcrest.Matchers.containsString;

import java.util.HashMap;
import java.util.Map;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;
import org.eclipse.jetty.websocket.jsr356.endpoints.TrackingSocket;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JsrEndpointFunctions_BadSignaturesTest
{
    private static ClientContainer container;

    @BeforeClass
    public static void initContainer()
    {
        container = new ClientContainer();
    }
    
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    private AvailableEncoders encoders;
    private AvailableDecoders decoders;
    private Map<String, String> uriParams = new HashMap<>();
    private EndpointConfig endpointConfig;

    public JsrEndpointFunctions_BadSignaturesTest()
    {
        endpointConfig = new EmptyClientEndpointConfig();
        encoders = new AvailableEncoders(endpointConfig);
        decoders = new AvailableDecoders(endpointConfig);
        uriParams = new HashMap<>();
    }

    private void assertBadSocket(TrackingSocket socket, String expectedString) throws Exception
    {
        JsrEndpointFunctions functions = new JsrEndpointFunctions(
                socket,
                container.getPolicy(),
                container.getExecutor(),
                encoders,
                decoders,
                uriParams,
                endpointConfig
        );

        expectedException.expect(InvalidSignatureException.class);
        expectedException.expectMessage(containsString(expectedString));
        functions.start();
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public class InvalidOpenCloseReasonSocket extends TrackingSocket
    {
        /**
         * Invalid Open Method Declaration (parameter type CloseReason)
         * @param reason the close reason
         */
        @OnOpen
        public void onOpen(CloseReason reason)
        {
            /* no impl */
        }
    }

    @Test
    public void testInvalidOpenCloseReasonSocket() throws Exception
    {
        assertBadSocket(new InvalidOpenCloseReasonSocket(), "onOpen");
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public static class InvalidOpenIntSocket extends TrackingSocket
    {
        /**
         * Invalid Open Method Declaration (parameter type int)
         * @param value the open value
         */
        @OnOpen
        public void onOpen(int value)
        {
            /* no impl */
        }
    }

    @Test
    public void testInvalidOpenIntSocket() throws Exception
    {
        assertBadSocket(new InvalidOpenIntSocket(), "onOpen");
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public static class InvalidOpenSessionIntSocket extends TrackingSocket
    {
        /**
         * Invalid Open Method Declaration (parameter of type int)
         * @param session the session for the open
         * @param count the open count
         */
        @OnOpen
        public void onOpen(Session session, int count)
        {
            /* no impl */
        }
    }

    @Test
    public void testInvalidOpenSessionIntSocket() throws Exception
    {
        assertBadSocket(new InvalidOpenSessionIntSocket(), "onOpen");
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public static class InvalidCloseIntSocket extends TrackingSocket
    {
        /**
         * Invalid Close Method Declaration (parameter type int)
         *
         * @param statusCode the status code
         */
        @OnClose
        public void onClose(int statusCode)
        {
            closeLatch.countDown();
        }
    }

    @Test
    public void testInvalidCloseIntSocket() throws Exception
    {
        assertBadSocket(new InvalidCloseIntSocket(), "onClose");
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public static class InvalidErrorErrorSocket extends TrackingSocket
    {
        /**
         * Invalid Error Method Declaration (parameter type Error)
         *
         * @param error the error
         */
        @OnError
        public void onError(Error error)
        {
            /* no impl */
        }
    }

    @Test
    public void testInvalidErrorErrorSocket() throws Exception
    {
        assertBadSocket(new InvalidErrorErrorSocket(), "onError");
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public static class InvalidErrorExceptionSocket extends TrackingSocket
    {
        /**
         * Invalid Error Method Declaration (parameter type Exception)
         *
         * @param e the extension
         */
        @OnError
        public void onError(Exception e)
        {
            /* no impl */
        }
    }

    @Test
    public void testInvalidErrorExceptionSocket() throws Exception
    {
        assertBadSocket(new InvalidErrorExceptionSocket(), "onError");
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public static class InvalidErrorIntSocket extends TrackingSocket
    {
        /**
         * Invalid Error Method Declaration (parameter type int)
         *
         * @param errorCount the error count
         */
        @OnError
        public void onError(int errorCount)
        {
            /* no impl */
        }
    }

    @Test
    public void testInvalidErrorIntSocket() throws Exception
    {
        assertBadSocket(new InvalidErrorIntSocket(), "onError");
    }

    // TODO: invalid return types
    // TODO: static methods
    // TODO: private or protected methods
    // TODO: abstract methods

}
