//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io.content;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IO;

/**
 * A {@link Content.Source} backed by an {@link OutputStream}.
 * <p>
 * Any bytes written to the {@link OutputStream} returned by {@link #getOutputStream()}
 * is converted to a {@link Content.Chunk} and returned from {@link #read()}. If
 * necessary, any {@link Runnable} passed to {@link #demand(Runnable)} is invoked.
 * @see AsyncContent
 */
public class OutputStreamContentSource implements Content.Source
{
    private final AsyncContent async = new AsyncContent();
    private final AsyncOutputStream output = new AsyncOutputStream();

    public OutputStream getOutputStream()
    {
        return output;
    }

    @Override
    public long getLength()
    {
        return async.getLength();
    }

    @Override
    public Content.Chunk read()
    {
        return async.read();
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        async.demand(demandCallback);
    }

    @Override
    public void fail(Throwable failure)
    {
        async.fail(failure);
    }

    private class AsyncOutputStream extends OutputStream
    {
        @Override
        public void write(int b) throws IOException
        {
            write(new byte[]{(byte)b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            try
            {
                FutureCallback callback = new FutureCallback();
                async.write(false, ByteBuffer.wrap(b, off, len), callback);
                callback.get();
            }
            catch (Throwable x)
            {
                throw IO.rethrow(x);
            }
        }

        @Override
        public void flush() throws IOException
        {
            async.flush();
        }

        @Override
        public void close()
        {
            async.close();
        }
    }
}
