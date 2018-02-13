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

import javax.websocket.Decoder;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.MessageSink;

public abstract class DecodedMessageSink<T extends Decoder> extends AbstractMessageSink
{
    protected final Logger LOG;
    private final T decoder;
    private final MethodHandle rawMethodHandle;
    private final MessageSink rawMessageSink;

    public DecodedMessageSink(JavaxWebSocketSession session, T decoder, MethodHandle methodHandle)
            throws NoSuchMethodException, IllegalAccessException
    {
        super(session, methodHandle);
        this.LOG = Log.getLogger(this.getClass());
        this.decoder = decoder;
        this.rawMethodHandle = newRawMethodHandle();
        this.rawMessageSink = newRawMessageSink(session, rawMethodHandle);
    }

    protected abstract MethodHandle newRawMethodHandle()
            throws NoSuchMethodException, IllegalAccessException;

    protected abstract MessageSink newRawMessageSink(JavaxWebSocketSession session, MethodHandle rawMethodHandle);

    public T getDecoder()
    {
        return decoder;
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        this.rawMessageSink.accept(frame, callback);
    }
}
