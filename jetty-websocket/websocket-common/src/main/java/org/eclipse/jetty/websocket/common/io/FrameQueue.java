//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.io;

import java.util.LinkedList;

import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection.FrameBytes;

/**
 * Queue for outgoing frames.
 */
@SuppressWarnings("serial")
public class FrameQueue extends LinkedList<FrameBytes>
{
    private Throwable failure;

    public void append(FrameBytes bytes)
    {
        synchronized (this)
        {
            if (isFailed())
            {
                // no changes when failed
                bytes.failed(failure);
                return;
            }
            addLast(bytes);
        }
    }

    public synchronized void fail(Throwable t)
    {
        synchronized (this)
        {
            if (isFailed())
            {
                // already failed.
                return;
            }

            failure = t;

            for (FrameBytes fb : this)
            {
                fb.failed(failure);
            }

            clear();
        }
    }

    public Throwable getFailure()
    {
        return failure;
    }

    public boolean isFailed()
    {
        return (failure != null);
    }

    public void prepend(FrameBytes bytes)
    {
        synchronized (this)
        {
            if (isFailed())
            {
                // no changes when failed
                bytes.failed(failure);
                return;
            }

            // TODO: make sure that we don't go in front of started but not yet finished frames.
            addFirst(bytes);
        }
    }
}
