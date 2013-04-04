//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.messages;

import java.io.InputStream;

import javax.websocket.MessageHandler;
import javax.websocket.MessageHandler.Whole;

import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.message.MessageInputStream;

public class BinaryStreamMessage extends MessageInputStream
{
    private final MessageHandlerWrapper msgWrapper;
    private final MessageHandler.Whole<InputStream> streamHandler;

    @SuppressWarnings("unchecked")
    public BinaryStreamMessage(EventDriver driver, MessageHandlerWrapper wrapper)
    {
        super(driver);
        this.msgWrapper = wrapper;
        this.streamHandler = (Whole<InputStream>)wrapper.getHandler();
    }
}
