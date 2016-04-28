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

package org.eclipse.jetty.websocket.jsr356.messages;

import java.util.function.Function;

import javax.websocket.MessageHandler;
import javax.websocket.MessageHandler.Whole;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.message.StringMessageSink;
import org.eclipse.jetty.websocket.jsr356.MessageHandlerWrapper;

/**
 * @deprecated should just use StringMessageSink directly
 */
@Deprecated
public class TextWholeMessage extends StringMessageSink
{
    private final MessageHandlerWrapper msgWrapper;
    private final MessageHandler.Whole<Object> wholeHandler;

    @SuppressWarnings("unchecked")
    public TextWholeMessage(WebSocketPolicy policy, Function<String, Void> onMessageFunction, MessageHandlerWrapper wrapper)
    {
        super(policy, onMessageFunction);
        this.msgWrapper = wrapper;
        this.wholeHandler = (Whole<Object>) wrapper.getHandler();
    }

/*    @SuppressWarnings("unchecked")
    @Override
    public void messageComplete()
    {
        finished = true;

        DecoderFactory.Wrapper decoder = msgWrapper.getDecoder();
        Decoder.Text<Object> textDecoder = (Decoder.Text<Object>)decoder.getDecoder();
        try
        {
            Object obj = textDecoder.decode(utf.toString());
            wholeHandler.onMessage(obj);
        }
        catch (DecodeException e)
        {
            throw new WebSocketException("Unable to decode text data",e);
        }
    }*/
}
