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

import java.nio.file.Path;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;

/**
 * <p>A {@link Content.Source} that provides the file content of the passed {@link Path}.</p>
 */
public class PathContentSource implements Content.Source
{
    private final Path _path;
    private final Content.Source _source;

    public PathContentSource(Path path)
    {
        this(path, null);
    }

    public PathContentSource(Path path, ByteBufferPool byteBufferPool)
    {
        this(path, byteBufferPool instanceof ByteBufferPool.Sized sized ? sized : new ByteBufferPool.Sized(byteBufferPool));
    }

    public PathContentSource(Path path, ByteBufferPool.Sized sizedBufferPool)
    {
        _path = path;
        _source = Content.Source.from(sizedBufferPool, path);
    }

    public Path getPath()
    {
        return _path;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        _source.demand(demandCallback);
    }

    @Override
    public void fail(Throwable failure)
    {
        _source.fail(failure);
    }

    @Override
    public void fail(Throwable failure, boolean last)
    {
        _source.fail(failure, last);
    }

    @Override
    public long getLength()
    {
        return _source.getLength();
    }

    @Override
    public Content.Chunk read()
    {
        return _source.read();
    }

    @Override
    public boolean rewind()
    {
        return _source.rewind();
    }
}
