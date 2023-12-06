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

import java.io.Closeable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.io.content.ChunksContentSource;

public class TestSource extends ChunksContentSource implements Closeable
{
    private final List<Content.Chunk> chunks;

    public TestSource(Content.Chunk... chunks)
    {
        super(Arrays.asList(chunks));
        this.chunks = Arrays.asList(chunks);
    }

    @Override
    public void close()
    {
        chunks.stream().filter(Objects::nonNull).forEach(Content.Chunk::release);
    }
}
