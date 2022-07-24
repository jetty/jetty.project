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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;

public class Trailers implements Content.Chunk
{
    private final HttpFields trailers;

    public Trailers(HttpFields trailers)
    {
        this.trailers = trailers;
    }

    @Override
    public ByteBuffer getByteBuffer()
    {
        return BufferUtil.EMPTY_BUFFER;
    }

    @Override
    public boolean isLast()
    {
        return true;
    }

    public HttpFields getTrailers()
    {
        return trailers;
    }

    @Override
    public void retain()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean release()
    {
        return true;
    }
}
