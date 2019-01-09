//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.autobahn;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AbstractTestFrameHandler;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketTimeoutException;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.eclipse.jetty.websocket.core.OpCode.TEXT;

class AutobahnFrameHandler extends AbstractTestFrameHandler
{
    private static Logger LOG = Log.getLogger(AutobahnFrameHandler.class);

    private AtomicBoolean open = new AtomicBoolean(false);

    @Override
    public void onOpen()
    {
        LOG.info("onOpen {}", getCoreSession());

        if (!open.compareAndSet(false, true))
            throw new IllegalStateException();
    }

    int count;

    @Override
    public void onText(Utf8StringBuilder utf8, Callback callback, boolean fin)
    {
        LOG.debug("onText {} {} {} {}", count++, utf8.length(), fin, getCoreSession());
        if (fin)
        {
            getCoreSession().sendFrame(new Frame(TEXT).setPayload(utf8.toString()),
                callback,
                false);
        }
        else
        {
            callback.succeeded();
        }
    }

    @Override
    public void onBinary(ByteBuffer payload, Callback callback, boolean fin)
    {
        LOG.debug("onBinary {} {} {}", payload == null?-1:payload.remaining(), fin, getCoreSession());
        if (fin)
        {
            Frame echo = new Frame(OpCode.BINARY);
            if (payload != null)
                echo.setPayload(payload);
            getCoreSession().sendFrame(echo, callback, false);
        }
        else
        {
            callback.succeeded();
        }
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        LOG.info("onClosed {}", closeStatus);
        if (!open.compareAndSet(true, false))
            LOG.warn("Already closed or not open {}", closeStatus);
    }

    @Override
    public void onError(Throwable cause)
    {
        if (cause instanceof WebSocketTimeoutException && open.get())
            LOG.info("timeout!");
        else
            LOG.warn("onError", cause);
    }
}
