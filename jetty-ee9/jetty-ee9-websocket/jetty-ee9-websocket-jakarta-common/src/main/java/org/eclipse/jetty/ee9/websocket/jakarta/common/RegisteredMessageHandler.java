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

package org.eclipse.jetty.ee9.websocket.jakarta.common;

import jakarta.websocket.MessageHandler;

public class RegisteredMessageHandler
{
    private byte websocketMessageType;
    private Class<?> handlerType;
    private MessageHandler messageHandler;

    public RegisteredMessageHandler(byte websocketMessageType, Class<?> handlerType, MessageHandler messageHandler)
    {
        this.websocketMessageType = websocketMessageType;
        this.handlerType = handlerType;
        this.messageHandler = messageHandler;
    }

    public byte getWebsocketMessageType()
    {
        return websocketMessageType;
    }

    public Class<?> getHandlerType()
    {
        return handlerType;
    }

    public MessageHandler getMessageHandler()
    {
        return messageHandler;
    }
}
