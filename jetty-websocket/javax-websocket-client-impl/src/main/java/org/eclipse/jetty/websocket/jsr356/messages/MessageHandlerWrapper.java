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

import javax.websocket.Decoder;
import javax.websocket.MessageHandler;

import org.eclipse.jetty.websocket.jsr356.DecoderWrapper;

/**
 * Expose a {@link MessageHandler} instance along with its associated {@link MessageHandlerMetadata}
 */
public class MessageHandlerWrapper
{
    private final MessageHandler handler;
    private final MessageHandlerMetadata metadata;
    private final DecoderWrapper decoder;

    public MessageHandlerWrapper(MessageHandler handler, MessageHandlerMetadata metadata, DecoderWrapper decoder)
    {
        this.handler = handler;
        this.metadata = metadata;
        this.decoder = decoder;
    }

    public DecoderWrapper getDecoder()
    {
        return decoder;
    }

    public MessageHandler getHandler()
    {
        return handler;
    }

    public MessageHandlerMetadata getMetadata()
    {
        return metadata;
    }

    public boolean isMessageType(Class<?> msgType)
    {
        return msgType.isAssignableFrom(metadata.getMessageClass());
    }

    /**
     * Flag for a onMessage() that wants partial messages.
     * <p>
     * This indicates the use of MessageHandler.{@link Partial}.
     * 
     * @return true for use of MessageHandler.{@link Partial}, false for use of MessageHandler.{@link Whole}
     */
    public boolean wantsPartialMessages()
    {
        return metadata.isPartialSupported();
    }

    /**
     * Flag for a onMessage() method that wants MessageHandler.{@link Whole} with a Decoder that is based on {@link Decoder.TextStream} or
     * {@link Decoder.BinaryStream}
     * 
     * @return true for Streaming based Decoder, false for normal decoder for whole messages.
     */
    public boolean wantsStreams()
    {
        return decoder.getMetadata().isStreamed();
    }
}
