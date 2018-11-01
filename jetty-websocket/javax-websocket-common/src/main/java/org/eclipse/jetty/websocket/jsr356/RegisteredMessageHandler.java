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

package org.eclipse.jetty.websocket.jsr356;

import javax.websocket.MessageHandler;

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
