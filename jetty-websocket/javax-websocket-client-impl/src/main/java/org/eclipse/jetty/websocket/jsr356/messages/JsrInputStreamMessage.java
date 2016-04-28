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

import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.function.Function;

import javax.websocket.Decoder;
import javax.websocket.Decoder.BinaryStream;

import org.eclipse.jetty.websocket.common.message.InputStreamMessageSink;
import org.eclipse.jetty.websocket.common.message.MessageInputStream;

/**
 * Handling of InputStreams (Binary messages) via javax.websocket
 * @deprecated Should just use InputStreamMessageSink directly (with decoder behind it)
 */
@Deprecated
public class JsrInputStreamMessage extends InputStreamMessageSink
{
    private final Decoder.BinaryStream<?> decoder;
    private MessageInputStream stream = null;

    public JsrInputStreamMessage(Executor executor, Function<InputStream, Void> function, BinaryStream<?> decoder)
    {
        super(executor, function);
        this.decoder = decoder;
    }

    /*@Override
    public void appendFrame(ByteBuffer framePayload, boolean fin) throws IOException
    {
        boolean first = (stream == null);

        stream = new MessageInputStream();
        stream.appendFrame(framePayload,fin);
        if (first)
        {
            executor.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        if (decoder != null)
                        {
                            Object o = decoder.decode(stream);
                            events.onObject(o);
                        }
                        else
                        {
                            events.onInputStream(stream);
                        }
                    }
                    catch (Throwable t)
                    {
                        events.onError(t);
                    }
                }
            });
        }
    }*/
}
