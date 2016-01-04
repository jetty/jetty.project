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

package org.eclipse.jetty.websocket.jsr356.messages;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.Decoder.Binary;
import javax.websocket.MessageHandler;
import javax.websocket.MessageHandler.Whole;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.message.SimpleBinaryMessage;
import org.eclipse.jetty.websocket.jsr356.DecoderFactory;
import org.eclipse.jetty.websocket.jsr356.MessageHandlerWrapper;

public class BinaryWholeMessage extends SimpleBinaryMessage
{
    private final MessageHandlerWrapper msgWrapper;
    private final MessageHandler.Whole<Object> wholeHandler;

    @SuppressWarnings("unchecked")
    public BinaryWholeMessage(EventDriver onEvent, MessageHandlerWrapper wrapper)
    {
        super(onEvent);
        this.msgWrapper = wrapper;
        this.wholeHandler = (Whole<Object>)wrapper.getHandler();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void messageComplete()
    {
        super.finished = true;

        byte data[] = out.toByteArray();

        DecoderFactory.Wrapper decoder = msgWrapper.getDecoder();
        Decoder.Binary<Object> binaryDecoder = (Binary<Object>)decoder.getDecoder();
        try
        {
            Object obj = binaryDecoder.decode(BufferUtil.toBuffer(data));
            wholeHandler.onMessage(obj);
        }
        catch (DecodeException e)
        {
            throw new WebSocketException("Unable to decode binary data",e);
        }
    }
}
