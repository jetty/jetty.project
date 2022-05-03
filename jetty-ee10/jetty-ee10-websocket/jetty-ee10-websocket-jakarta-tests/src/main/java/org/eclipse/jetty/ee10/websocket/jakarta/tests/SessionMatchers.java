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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import jakarta.websocket.MessageHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.matchers.IsMessageHandlerType;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.matchers.IsMessageHandlerTypeRegistered;
import org.hamcrest.Matcher;

public final class SessionMatchers
{
    public static Matcher<JakartaWebSocketSession> isMessageHandlerTypeRegistered(MessageType messageType)
    {
        return IsMessageHandlerTypeRegistered.isMessageHandlerTypeRegistered(messageType);
    }

    public static Matcher<MessageHandler> isMessageHandlerType(JakartaWebSocketSession session, MessageType messageType)
    {
        return IsMessageHandlerType.isMessageHandlerType(session, messageType);
    }
}
