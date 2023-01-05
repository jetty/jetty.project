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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.io.content.PathContentSource;

/**
 * <p>A {@link Request.Content} for files using JDK 7's {@code java.nio.file} APIs.</p>
 * <p>It is possible to specify a buffer size used to read content from the stream,
 * by default 4096 bytes, whether the buffer should be direct or not, and a
 * {@link RetainableByteBufferPool} from which {@code ByteBuffer}s will be acquired
 * to read from the {@code Path}.</p>
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
        this(contentType, filePath, null);
        setBufferSize(bufferSize);
    }

    public PathRequestContent(String contentType, Path filePath, RetainableByteBufferPool bufferPool) throws IOException
    {
        super(filePath, bufferPool);
        this.contentType = contentType;
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }
}
