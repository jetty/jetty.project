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

package org.eclipse.jetty.ee10.websocket.jakarta.common;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JakartaWebSocketFrameHandlerBadSignaturesTest extends AbstractJakartaWebSocketFrameHandlerTest
{
    private void assertBadSocket(Object socket, String expectedString) throws Exception
    {
        Exception e = assertThrows(InvalidSignatureException.class, () -> newJakartaFrameHandler(socket));
        assertThat(e.getMessage(), containsString(expectedString));
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public class InvalidOpenCloseReasonSocket
    {
        /**
         * Invalid Open Method Declaration (parameter type CloseReason)
         *
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
         *
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
         *
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

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public static class InvalidErrorNoParamSocket
    {
        /**
         * Invalid Error Method Declaration (missing required Throwable type)
         */
        @OnError
        public void onError()
        {
            /* no impl */
        }
    }

    @Test
    public void testInvalidErrorNoParamSocket() throws Exception
    {
        assertBadSocket(new InvalidErrorNoParamSocket(), "onError");
    }

    @SuppressWarnings("UnusedParameters")
    @ClientEndpoint
    public static class InvalidErrorSessionOnlySocket
    {
        /**
         * Invalid Error Method Declaration (missing required Throwable type)
         */
        @OnError
        public void onError(Session session)
        {
            /* no impl */
        }
    }

    @Test
    public void testInvalidErrorSessionOnlySocket() throws Exception
    {
        assertBadSocket(new InvalidErrorSessionOnlySocket(), "onError");
    }

    // TODO: invalid return types
    // TODO: static methods
    // TODO: private or protected methods
    // TODO: abstract methods
}
