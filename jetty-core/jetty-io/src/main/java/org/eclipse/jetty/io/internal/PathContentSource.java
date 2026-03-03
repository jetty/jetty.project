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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * A {@link ByteChannelContentSource} for a {@link Path}
 */
public class PathContentSource extends SeekableByteChannelContentSource implements Content.Transferable.From
{
    private final Path _path;

    public PathContentSource(Path path)
    {
        this(null, path, 0L, -1L);
    }

    public PathContentSource(ByteBufferPool.Sized byteBufferPool, Path path)
    {
        this(byteBufferPool, path, 0L, -1L);
    }

    public PathContentSource(ByteBufferPool.Sized byteBufferPool, Path path, long offset, long length)
    {
        super(byteBufferPool, null, offset, TypeUtil.checkOffsetLengthSize(offset, length, size(path)));
        _path = path;
    }

    public Path getPath()
    {
        return _path;
    }

    @Override
    public FileChannel getByteChannel()
    {
        return (FileChannel)super.getByteChannel();
    }

    @Override
    protected SeekableByteChannel open() throws IOException
    {
        return Files.newByteChannel(_path, StandardOpenOption.READ);
    }

    @Override
    public Content.Chunk read()
    {
        Content.Chunk chunk = super.read();
        if (chunk != null && chunk.isLast() && chunk.isEmpty())
            IO.close(getByteChannel());
        return chunk;
    }

    @Override
    public Seekable slice(long position, int length)
    {
        return new PathContentSource(getByteBufferPool(), getPath(), position, length);
    }

    @Override
    public boolean transferTo(Content.Sink sink, Callback callback)
    {
        try (AutoLock ignored = lock())
        {
            Content.Chunk terminal = lockedEnsureOpenOrTerminal();
            if (Content.Chunk.isFailure(terminal))
                return false;
            if (!(sink instanceof Content.Transferable.To to))
                return false;
            Callback cb = Callback.from(callback.getInvocationType(), () ->
            {
                position(position() + getLength());
                callback.succeeded();
            }, callback::failed);
            return to.transferFrom(getByteChannel(), getOffset(), getLength(), cb);
        }
    }

    private static long size(Path path)
    {
        try
        {
            return Files.size(path);
        }
        catch (IOException e)
        {
            return -1L;
        }
    }
}
