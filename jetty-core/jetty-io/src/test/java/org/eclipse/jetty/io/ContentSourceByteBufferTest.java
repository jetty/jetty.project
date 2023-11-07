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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.internal.ContentSourceByteBuffer;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

public class ContentSourceByteBufferTest
{
    @Test
    public void testTransientErrorsBecomeTerminalErrors() throws Exception
    {
        TimeoutException originalFailure = new TimeoutException("timeout");
        TestSource originalSource = new TestSource(
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            null,
            Content.Chunk.from(originalFailure, false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), true)
        );

        Promise.Completable<ByteBuffer> promise = new Promise.Completable<>();
        ContentSourceByteBuffer contentSourceByteBuffer = new ContentSourceByteBuffer(originalSource, promise);
        contentSourceByteBuffer.run();
        try
        {
            promise.get();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), sameInstance(originalFailure));
        }

        Content.Chunk chunk = originalSource.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(chunk.getFailure(), sameInstance(originalFailure));
    }
}
