//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.client.jsr356;

import static org.hamcrest.Matchers.containsString;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.function.JsrEndpointFunctions;
import org.junit.Test;

public class JsrEndpointFunctions_BadSignaturesTest extends AbstractJsrEndpointFunctionsTest
{
    private void assertBadSocket(Object socket, String expectedString) throws Exception
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
    public class InvalidOpenCloseReasonSocket
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
    public static class InvalidOpenIntSocket
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
    public static class InvalidOpenSessionIntSocket
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
    public static class InvalidCloseIntSocket
    {
        /**
         * Invalid Close Method Declaration (parameter type int)
         *
         * @param statusCode the status code
         */
        @OnClose
        public void onClose(int statusCode)
        {
            /* no impl */
        }
    }

    @Test
    public void testInvalidCloseIntSocket() throws Exception
    {
        assertBadSocket(new InvalidCloseIntSocket(), "onClose");
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public static class InvalidErrorErrorSocket
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
    public static class InvalidErrorExceptionSocket
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
    public static class InvalidErrorIntSocket
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
