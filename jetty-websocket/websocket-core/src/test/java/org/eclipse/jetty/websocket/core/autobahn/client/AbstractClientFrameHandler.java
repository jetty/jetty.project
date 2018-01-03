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

package org.eclipse.jetty.websocket.core.autobahn.client;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AbstractFrameHandler;
import org.eclipse.jetty.websocket.core.CloseStatus;

public abstract class AbstractClientFrameHandler extends AbstractFrameHandler
{
    protected final Logger LOG;

    public AbstractClientFrameHandler()
    {
        LOG = Log.getLogger(this.getClass());
    }

    @Override
    public void onOpen()
    {
        LOG.debug("onOpen()");
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        LOG.debug("onClosed({})",closeStatus);
    }

    @Override
    public void onText(Utf8StringBuilder utf8, Callback callback, boolean fin)
    {
        onWholeText(utf8.toString());
        callback.succeeded();
    }

    protected void onWholeText(String message)
    {
    }

    @Override
    public void onBinary(ByteBuffer payload, Callback callback, boolean fin)
    {
        onWholeBinary(payload);
        callback.succeeded();
    }

    protected void onWholeBinary(ByteBuffer payload)
    {
    }

    /**
     * Make a copy of a byte buffer.
     * <p>
     * This is important in some tests, as the underlying byte buffer contained in a Frame can be modified through
     * masking and make it difficult to compare the results in the fuzzer.
     *
     * @param payload the payload to copy
     * @return a new byte array of the payload contents
     */
    @SuppressWarnings("Duplicates")
    public ByteBuffer copyOf(ByteBuffer payload)
    {
        if (payload == null)
            return null;

        ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
        copy.put(payload.slice());
        copy.flip();
        return copy;
    }
}
