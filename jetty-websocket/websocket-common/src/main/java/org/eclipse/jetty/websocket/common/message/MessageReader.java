//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.message;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.websocket.api.Session;

/**
 * Support class for reading a (single) WebSocket TEXT message via a Reader.
 * <p>
 * In compliance to the WebSocket spec, this reader always uses the UTF8 {@link Charset}.
 */
public class MessageReader extends InputStreamReader implements MessageAppender
{
    private final MessageInputStream stream;

    public MessageReader(Session session)
    {
        this(new MessageInputStream(session));
    }

    public MessageReader(MessageInputStream stream)
    {
        super(stream, StandardCharsets.UTF_8);
        this.stream = stream;
    }

    @Override
    public void appendFrame(ByteBuffer payload, boolean isLast) throws IOException
    {
        this.stream.appendFrame(payload, isLast);
    }

    @Override
    public void messageComplete()
    {
        this.stream.messageComplete();
    }

    public void handlerComplete()
    {
        this.stream.handlerComplete();
    }
}
