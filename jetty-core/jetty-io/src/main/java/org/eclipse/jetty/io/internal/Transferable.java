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
import java.nio.channels.WritableByteChannel;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;

public class Transferable
{
    private Transferable()
    {
    }

    public static void transfer(FileChannel fileChannel, long offset, long length, WritableByteChannel writeChannel, Callback callback)
    {
        Transferrer transferrer = new Transferrer(fileChannel, offset, length, writeChannel, callback);
        transferrer.iterate();
    }

    public interface From
    {
        boolean transferTo(Content.Sink sink, long length, Callback callback);
    }

    public interface To
    {
        boolean transferFrom(FileChannel fileChannel, long offset, long length, Callback callback);
    }

    private static class Transferrer extends IteratingNestedCallback
    {
        private final FileChannel fileChannel;
        private final long position;
        private final long length;
        private final WritableByteChannel writableChannel;
        private long transferred;

        private Transferrer(FileChannel fileChannel, long position, long length, WritableByteChannel writableChannel, Callback callback)
        {
            super(callback);
            this.fileChannel = fileChannel;
            this.position = position;
            this.length = length;
            this.writableChannel = writableChannel;
        }

        @Override
        protected Action process() throws Throwable
        {
            // TODO: should I set writeInterest() in NIO if TCP congestion?
            transferred += fileChannel.transferTo(position + transferred, length - transferred, writableChannel);
            // TODO: call this.succeeded()
            if (transferred == length)
                return Action.SUCCEEDED;
            return Action.SCHEDULED;
        }
    }
}
