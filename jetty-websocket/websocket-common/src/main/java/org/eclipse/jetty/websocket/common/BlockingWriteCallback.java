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

package org.eclipse.jetty.websocket.common;

import java.io.IOException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.websocket.api.WriteCallback;

/**
 * Extends a {@link SharedBlockingCallback} to a WebSocket {@link WriteCallback}
 */
public class BlockingWriteCallback extends SharedBlockingCallback
{
    public BlockingWriteCallback()
    {
    }

    public WriteBlocker acquireWriteBlocker() throws IOException
    {
        return new WriteBlocker(acquire());
    }

    public static class WriteBlocker implements WriteCallback, Callback, AutoCloseable
    {
        private final Blocker blocker;

        protected WriteBlocker(Blocker blocker)
        {
            this.blocker = blocker;
        }

        @Override
        public InvocationType getInvocationType()
        {
            // The callback does not block, only the writer blocks
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public void writeFailed(Throwable x)
        {
            blocker.failed(x);
        }

        @Override
        public void writeSuccess()
        {
            blocker.succeeded();
        }

        @Override
        public void succeeded()
        {
            blocker.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            blocker.failed(x);
        }

        @Override
        public void close()
        {
            blocker.close();
        }

        public void block() throws IOException
        {
            blocker.block();
        }
    }
}
