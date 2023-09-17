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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.IO;

/**
 * <p>An {@link InputStream} that is backed by a {@link Content.Source}.
 * The read methods are implemented by calling {@link Content.Source#read()}.
 * Any {@link Content.Chunk}s read are released once all their content
 * has been read.
 * </p>
 */
public class ContentSourceInputStream extends InputStream
{
    private final Blocker.Shared blocking = new Blocker.Shared();
    private final byte[] oneByte = new byte[1];
    private final Content.Source content;
    private Content.Chunk chunk;

    public ContentSourceInputStream(Content.Source content)
    {
        this.content = content;
    }

    @Override
    public int read() throws IOException
    {
        int read = read(oneByte, 0, 1);
        return read < 0 ? -1 : oneByte[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        while (true)
        {
            if (chunk != null)
            {
                if (Content.Chunk.isFailure(chunk))
                {
                    Content.Chunk c = chunk;
                    chunk = null;
                    throw IO.rethrow(c.getFailure());
                }

                ByteBuffer byteBuffer = chunk.getByteBuffer();
                if (chunk.isLast() && !byteBuffer.hasRemaining())
                    return -1;

                int l = Math.min(byteBuffer.remaining(), len);
                byteBuffer.get(b, off, l);
                if (!byteBuffer.hasRemaining())
                {
                    chunk.release();
                    chunk = chunk.isLast() ? Content.Chunk.EOF : null;
                }
                return l;
            }

            chunk = content.read();

            if (chunk == null)
            {
                try (Blocker.Runnable callback = blocking.runnable())
                {
                    content.demand(callback);
                    callback.block();
                }
            }
        }
    }

    @Override
    public int available() throws IOException
    {
        ByteBuffer available = chunk == null ? null : chunk.getByteBuffer();
        if (available != null)
            return available.remaining();
        return 0;
    }

    @Override
    public void close()
    {
        while (true)
        {
            // If we have already reached a real EOF or a persistent failure, close is a noop.
            if (chunk == Content.Chunk.EOF || Content.Chunk.isFailure(chunk, true))
                return;

            // If we have a chunk here, then it needs to be released
            if (chunk != null)
            {
                chunk.release();
                chunk = Content.Chunk.next(chunk);

                // if the chunk was a last chunk (but not an instanceof EOF), then nothing more to do
                if (chunk != null && chunk.isLast())
                    return;
            }

            // read any available chunks
            chunk = content.read();
            if (chunk == null)
            {
                // This is an abnormal close before EOF
                Throwable closed = new IOException("closed before EOF");
                chunk = Content.Chunk.from(closed);
                content.fail(closed);
                return;
            }
        }
    }
}
