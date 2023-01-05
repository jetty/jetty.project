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

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.io.content.ByteBufferContentSource;

/**
 * <p>A {@link Request.Content} for {@link ByteBuffer}s.</p>
 * <p>The position and limit of the {@link ByteBuffer}s passed to the constructor are not modified;
 * content production returns a {@link ByteBuffer#slice() slice} of the original {@link ByteBuffer}.
 */
public class ByteBufferRequestContent extends ByteBufferContentSource implements Request.Content
{
    private final String contentType;

    public ByteBufferRequestContent(ByteBuffer... byteBuffers)
    {
        this("application/octet-stream", byteBuffers);
    }

    public ByteBufferRequestContent(String contentType, ByteBuffer... byteBuffers)
    {
        this(contentType, List.of(byteBuffers));
    }

    public ByteBufferRequestContent(String contentType, Collection<ByteBuffer> byteBuffers)
    {
        super(byteBuffers);
        this.contentType = contentType;
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }
}
