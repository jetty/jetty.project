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

package org.eclipse.jetty.client.util;

import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.content.PathContentSource;

/**
 * <p>A {@link Request.Content} for files using JDK 7's {@code java.nio.file} APIs.</p>
 * <p>It is possible to specify a buffer size used to read content from the stream,
 * by default 4096 bytes, and whether the buffer should be direct or not.</p>
 * <p>If a {@link ByteBufferPool} is provided via {@link #setByteBufferPool(ByteBufferPool)},
 * the buffer will be allocated from that pool, otherwise one buffer will be
 * allocated and used to read the file.</p>
 */
public class PathRequestContent extends PathContentSource implements Request.Content
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
        this(contentType, filePath, 4096);
    }

    public PathRequestContent(String contentType, Path filePath, int bufferSize) throws IOException
    {
        super(filePath);
        this.contentType = contentType;
        setBufferSize(bufferSize);
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    @Override
    public boolean rewind()
    {
        return super.rewind();
    }
}
