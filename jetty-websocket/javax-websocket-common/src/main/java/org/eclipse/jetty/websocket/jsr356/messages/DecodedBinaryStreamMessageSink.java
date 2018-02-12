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

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import javax.websocket.CloseReason;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;

import org.eclipse.jetty.websocket.core.CloseException;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.MessageSink;

public class DecodedBinaryStreamMessageSink<T> extends DecodedMessageSink<Decoder.BinaryStream<T>>
{
    public DecodedBinaryStreamMessageSink(JavaxWebSocketSession session,
                                          Decoder.BinaryStream<T> decoder,
                                          MethodHandle methodHandle)
            throws NoSuchMethodException, IllegalAccessException
    {
        super(session, decoder, methodHandle);
    }

    @Override
    protected MethodHandle newRawMethodHandle() throws NoSuchMethodException, IllegalAccessException
    {
        return MethodHandles.lookup().findVirtual(DecodedBinaryStreamMessageSink.class,
                "onStreamStart", MethodType.methodType(void.class, InputStream.class))
                .bindTo(this);
    }

    @Override
    protected MessageSink newRawMessageSink(JavaxWebSocketSession session, MethodHandle rawMethodHandle)
    {
        return new InputStreamMessageSink(session, rawMethodHandle);
    }

    @SuppressWarnings("Duplicates")
    public void onStreamStart(InputStream stream)
    {
        try
        {
            T obj = getDecoder().decode(stream);
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
