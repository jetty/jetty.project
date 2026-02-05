//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io.internal;

import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Transferable
{
    private Transferable()
    {
    }

    public static void transfer(FileChannel sourceChannel, long offset, long length, SocketChannelEndPoint endPoint, Callback callback)
    {
        Transferrer transferrer = new Transferrer(sourceChannel, offset, length, endPoint, callback);
        transferrer.iterate();
    }

    public interface From
    {
        boolean transferTo(Content.Sink sink, Callback callback);
    }

    public interface To
    {
        boolean transferFrom(FileChannel fileChannel, long offset, long length, Callback callback);
    }

    private static class Transferrer extends IteratingNestedCallback
    {
        private static final Logger LOG = LoggerFactory.getLogger(Transferrer.class);

        private final FileChannel fileChannel;
        private final long offset;
        private final long length;
        private final SocketChannelEndPoint endPoint;
        private long transferred;

        private Transferrer(FileChannel fileChannel, long offset, long length, SocketChannelEndPoint endPoint, Callback callback)
        {
            super(callback);
            this.fileChannel = fileChannel;
            this.offset = offset;
            this.length = length;
            this.endPoint = endPoint;
        }

        @Override
        protected Action process() throws Throwable
        {
            long count = length - transferred;
            if (count == 0)
            {
                return Action.SUCCEEDED;
            }

            SocketChannel channel = endPoint.getChannel();

            long transfer = fileChannel.transferTo(offset + transferred, count, channel);
            transferred += transfer;

            if (LOG.isDebugEnabled())
                LOG.debug("Transferred {}/{} bytes from {} to {}", transfer, count, fileChannel, channel);

            if (transfer > 0)
            {
                endPoint.notIdle();
                succeeded();
                return Action.SCHEDULED;
            }

            endPoint.onIncompleteTransfer(this);
            return Action.SCHEDULED;
        }
    }
}
