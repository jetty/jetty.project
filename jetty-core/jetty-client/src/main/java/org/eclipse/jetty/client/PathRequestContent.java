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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.content.ByteChannelContentSource;

/**
 * <p>A {@link Request.Content} for files using JDK 7's {@code java.nio.file} APIs.</p>
 * <p>It is possible to specify a buffer size used to read content from the stream,
 * by default 4096 bytes, whether the buffer should be direct or not, and a
 * {@link ByteBufferPool} from which {@code ByteBuffer}s will be acquired
 * to read from the {@code Path}.</p>
 */
public class PathRequestContent extends ByteChannelContentSource.PathContentSource implements Request.Content
{
    private final String contentType;

    public PathRequestContent(Path filePath) throws IOException
    {
        this(filePath, 4096);
    }

    public PathRequestContent(Path filePath, int bufferSize) throws IOException
    {
        this("application/octet-stream", filePath, bufferSize);
    }

    public PathRequestContent(String contentType, Path filePath) throws IOException
    {
        this(contentType, filePath, new ByteBufferPool.Sized(null));
    }

    public PathRequestContent(String contentType, Path filePath, int bufferSize) throws IOException
    {
        this(contentType, filePath, new ByteBufferPool.Sized(null, false, bufferSize));
    }

    public PathRequestContent(String contentType, Path filePath, ByteBufferPool bufferPool) throws IOException
    {
        this(contentType, filePath, new ByteBufferPool.Sized(bufferPool));
    }

    public PathRequestContent(String contentType, Path filePath, ByteBufferPool bufferPool, boolean direct, int bufferSize) throws IOException
    {
        this(contentType, filePath, new ByteBufferPool.Sized(bufferPool, direct, bufferSize));
    }

    public PathRequestContent(String contentType, Path filePath, ByteBufferPool.Sized bufferPool) throws IOException
    {
        super(bufferPool, filePath);
        this.contentType = contentType;
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    /**
     * @deprecated Use the {@link ByteBufferPool.Sized} in a constructor
     */
    @Deprecated (forRemoval = true)
    public void setUseDirectByteBuffers()
    {
    }

    /**
     * @deprecated Use the {@link ByteBufferPool.Sized} in a constructor
     */
    @Deprecated (forRemoval = true)
    public void setBufferSize(int bufferSize)
    {
    }
}
