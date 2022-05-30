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

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.util.Callback;

public class AsyncRequestContent extends AsyncContent implements Request.Content
{
    private final String contentType;

    public AsyncRequestContent(ByteBuffer... buffers)
    {
        this("application/octet-stream", buffers);
    }

    public AsyncRequestContent(String contentType, ByteBuffer... buffers)
    {
        this.contentType = contentType;
        Stream.of(buffers).forEach(buffer -> write(buffer, Callback.NOOP));
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    public void write(ByteBuffer buffer, Callback callback)
    {
        write(Content.Chunk.from(buffer, false), callback);
    }
}
