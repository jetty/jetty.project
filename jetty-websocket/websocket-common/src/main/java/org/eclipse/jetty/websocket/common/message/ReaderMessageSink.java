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

package org.eclipse.jetty.websocket.common.message;

import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ReaderMessageSink implements MessageSink
{
    private static final Logger LOG = Log.getLogger(ReaderMessageSink.class);
    private final Executor executor;
    private final Function<Reader, Void> onStreamFunction;
    private MessageReader stream;

    public ReaderMessageSink(Executor executor, Function<Reader, Void> function)
    {
        this.executor = executor;
        this.onStreamFunction = function;
    }

    @Override
    public void accept(ByteBuffer payload, Boolean fin)
    {
        try
        {
            boolean first = false;

            if (stream == null)
            {
                stream = new MessageReader(new MessageInputStream());
                first = true;
            }

            stream.accept(payload,fin);
            if (first)
            {
                executor.execute(() -> {
                    // processing of errors is the responsibility
                    // of the stream function
                    if(LOG.isDebugEnabled())
                        LOG.debug("onStreamFunction.apply({})", stream);
                    onStreamFunction.apply(stream);
                });
            }
        }
        finally
        {
            if (fin)
            {
                if(LOG.isDebugEnabled())
                    LOG.debug("stream.awaitClose() - {}", stream);
                stream.awaitClose();
                if(LOG.isDebugEnabled())
                    LOG.debug("stream recycled - {}", stream);
                stream = null;
            }
        }
    }
}
