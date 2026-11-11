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

package org.eclipse.jetty.io.content;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/**
 * <p>A {@link Content.Source} that provides content asynchronously through an {@link OutputStream}.</p>
 * <p>Bytes written to the {@link OutputStream} returned by {@link #getOutputStream()}
 * are converted to a {@link Content.Chunk} and returned from {@link #read()}.</p>
 * <p>The {@code OutputStream} must be closed to signal that all the content has been written.</p>
 *
 * @see AsyncContentSource
 */
public class OutputStreamContentSource implements Content.Source, Closeable
{
    private final AsyncContentSource async = new AsyncContentSource();
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

    @Override
    public void close()
    {
        output.close();
    }

    private class AsyncOutputStream extends OutputStream
    {
        private boolean failed;

        @Override
        public void write(int b) throws IOException
        {
            write(new byte[]{(byte)b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            try (Blocker.Callback callback = Blocker.callback())
            {
                // The copy is necessary, since the byte[] may still be in
                // use when the callback is completed, and returning from
                // this method typically means that the byte[] can be reused.
                byte[] bytes = Arrays.copyOfRange(b, off, off + len);
                try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(bytes))
                {
                    async.offer(false, buffer, callback);
                }
                callback.block();
            }
            catch (Throwable x)
            {
                handleException(x);
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

        private void handleException(Throwable x) throws IOException
        {
            IOException failure = IO.rethrow(x);
            if (failed)
                throw new IOException(failure.toString());
            failed = true;
            throw failure;
        }
    }
}
