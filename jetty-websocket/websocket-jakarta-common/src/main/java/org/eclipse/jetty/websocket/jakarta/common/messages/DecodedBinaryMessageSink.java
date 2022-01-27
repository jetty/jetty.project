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

package org.eclipse.jetty.websocket.jakarta.common.messages;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.List;

import jakarta.websocket.CloseReason;
import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.exception.CloseException;
import org.eclipse.jetty.websocket.core.internal.messages.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.core.internal.messages.MessageSink;
import org.eclipse.jetty.websocket.jakarta.common.JakartaWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DecodedBinaryMessageSink<T> extends AbstractDecodedMessageSink.Basic<Decoder.Binary<T>>
{
    private static final Logger LOG = LoggerFactory.getLogger(DecodedBinaryMessageSink.class);

    public DecodedBinaryMessageSink(CoreSession session, MethodHandle methodHandle, List<RegisteredDecoder> decoders)
    {
        super(session, methodHandle, decoders);
    }

    @Override
    MessageSink newMessageSink(CoreSession coreSession) throws Exception
    {
        MethodHandle methodHandle = JakartaWebSocketFrameHandlerFactory.getServerMethodHandleLookup()
            .findVirtual(DecodedBinaryMessageSink.class, "onWholeMessage", MethodType.methodType(void.class, ByteBuffer.class))
            .bindTo(this);
        return new ByteBufferMessageSink(coreSession, methodHandle);
    }

    public void onWholeMessage(ByteBuffer wholeMessage)
    {
        for (Decoder.Binary<T> decoder : _decoders)
        {
            if (decoder.willDecode(wholeMessage))
            {
                try
                {
                    T obj = decoder.decode(wholeMessage);
                    invoke(obj);
                    return;
                }
                catch (DecodeException e)
                {
                    throw new CloseException(CloseReason.CloseCodes.CANNOT_ACCEPT.getCode(), "Unable to decode", e);
                }
            }
        }

        LOG.warn("Message lost, willDecode() has returned false for all decoders in the decoder list.");
    }
}
