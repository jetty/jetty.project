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

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.websocket.MessageHandler;

import org.eclipse.jetty.websocket.common.message.MessageSink;

/**
 * Partial BINARY MessageAppender for MessageHandler.Partial&lt;ByteBuffer&gt; interface
 */
public class BinaryBufferPartialMessage implements MessageSink
{
    private final MessageHandler.Partial<ByteBuffer> partialHandler;

    public BinaryBufferPartialMessage(MessageHandler.Partial<ByteBuffer> handler)
    {
        this.partialHandler = handler;
    }

    @Override
    public void appendFrame(ByteBuffer payload, boolean isLast) throws IOException
    {
                // No decoders for Partial messages per JSR-356 (PFD1 spec)
        partialHandler.onMessage(payload.slice(),isLast);
    }

    @Override
    public void messageComplete()
    {
        /* nothing to do here */
    }
}
