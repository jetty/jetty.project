//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests;

import javax.websocket.MessageHandler;

import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.tests.matchers.IsMessageHandlerType;
import org.eclipse.jetty.websocket.jsr356.tests.matchers.IsMessageHandlerTypeRegistered;
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
