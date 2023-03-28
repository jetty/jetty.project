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

package org.eclipse.jetty.websocket.javax.tests;

import javax.websocket.MessageHandler;

import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.javax.tests.matchers.IsMessageHandlerType;
import org.eclipse.jetty.websocket.javax.tests.matchers.IsMessageHandlerTypeRegistered;
import org.hamcrest.Matcher;

public final class SessionMatchers
{
    public static Matcher<JavaxWebSocketSession> isMessageHandlerTypeRegistered(MessageType messageType)
    {
        return IsMessageHandlerTypeRegistered.isMessageHandlerTypeRegistered(messageType);
    }

    public static Matcher<MessageHandler> isMessageHandlerType(JavaxWebSocketSession session, MessageType messageType)
    {
        return IsMessageHandlerType.isMessageHandlerType(session, messageType);
    }
}
