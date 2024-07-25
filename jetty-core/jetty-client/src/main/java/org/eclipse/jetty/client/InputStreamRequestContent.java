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

import java.io.InputStream;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.content.InputStreamContentSource;

/**
 * <p>A {@link Request.Content} that produces content from an {@link InputStream}.</p>
 * <p>The input stream is read once and therefore fully consumed.</p>
 * <p>It is possible to specify, at the constructor, a buffer size used to read
 * content from the stream, by default 4096 bytes.</p>
 * <p>The {@link InputStream} passed to the constructor is by default closed
 * when is it fully consumed.</p>
 */
public class InputStreamRequestContent extends InputStreamContentSource implements Request.Content
{
    private final String contentType;

    /**
     * @deprecated use {@link #InputStreamRequestContent(String, InputStream, ByteBufferPool.Sized)} instead.
     */
    @Deprecated
    public InputStreamRequestContent(InputStream stream)
    {
        this(stream, 4096);
    }

    /**
     * @deprecated use {@link #InputStreamRequestContent(String, InputStream, ByteBufferPool.Sized)} instead.
     */
    @Deprecated
    public InputStreamRequestContent(InputStream stream, int bufferSize)
    {
        this("application/octet-stream", stream, bufferSize);
    }

    /**
     * @deprecated use {@link #InputStreamRequestContent(String, InputStream, ByteBufferPool.Sized)} instead.
     */
    @Deprecated
    public InputStreamRequestContent(String contentType, InputStream stream, int bufferSize)
    {
        this(contentType, stream, new ByteBufferPool.Sized(null, false, bufferSize));
    }

    /**
     * @deprecated use {@link #InputStreamRequestContent(String, InputStream, ByteBufferPool.Sized)} instead.
     */
    @Deprecated
    public InputStreamRequestContent(String contentType, InputStream stream)
    {
        this(contentType, stream, null);
    }

    /**
     * @deprecated use {@link #InputStreamRequestContent(String, InputStream, ByteBufferPool.Sized)} instead.
     */
    @Deprecated
    public InputStreamRequestContent(String contentType, InputStream stream, ByteBufferPool bufferPool)
    {
        this(contentType, stream, new ByteBufferPool.Sized(bufferPool));
    }

    public InputStreamRequestContent(String contentType, InputStream stream, ByteBufferPool.Sized bufferPool)
    {
        super(stream, bufferPool);
        this.contentType = contentType;
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }
}
