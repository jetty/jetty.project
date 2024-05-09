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

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class TestSink implements Content.Sink
{
    private List<Content.Chunk> accumulatedChunks = new ArrayList<>();

    @Override
    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        accumulatedChunks.add(Content.Chunk.from(BufferUtil.copy(byteBuffer), last));
        callback.succeeded();
    }

    public List<Content.Chunk> takeAccumulatedChunks()
    {
        List<Content.Chunk> chunks = accumulatedChunks;
        accumulatedChunks = null;
        return chunks;
    }
}
