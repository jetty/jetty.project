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

package org.eclipse.jetty.websocket.jsr356.messages;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import javax.websocket.CloseReason;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.core.CloseException;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.MessageSink;

public class DecodedTextMessageSink<T> extends DecodedMessageSink<Decoder.Text<T>>
{
    public DecodedTextMessageSink(JavaxWebSocketSession session,
                                  Decoder.Text<T> decoder,
                                  MethodHandle methodHandle)
            throws NoSuchMethodException, IllegalAccessException
    {
        super(session, decoder, methodHandle);
    }

    @Override
    protected MethodHandle newRawMethodHandle() throws NoSuchMethodException, IllegalAccessException
    {
        return MethodHandles.lookup().findVirtual(DecodedTextMessageSink.class,
                "onWholeMessage", MethodType.methodType(void.class, String.class))
                .bindTo(this);
    }

    @Override
    protected MessageSink newRawMessageSink(JavaxWebSocketSession session, MethodHandle rawMethodHandle)
    {
        return new StringMessageSink(session, rawMethodHandle);
    }

    @SuppressWarnings("Duplicates")
    public void onWholeMessage(String wholeMessage)
    {
        if (!getDecoder().willDecode(wholeMessage))
        {
            LOG.warn("Message lost, decoder " + getDecoder().getClass().getName() + "#willDecode() has rejected it.");
            return;
        }

        try
        {
            T obj = getDecoder().decode(wholeMessage);
            methodHandle.invoke(obj);
        }
        catch (DecodeException e)
        {
            throw new CloseException(CloseReason.CloseCodes.CANNOT_ACCEPT.getCode(), "Unable to decode", e);
        }
        catch (Throwable t)
        {
            throw new CloseException(CloseReason.CloseCodes.CANNOT_ACCEPT.getCode(), "Endpoint notification error", t);
        }
    }
}
