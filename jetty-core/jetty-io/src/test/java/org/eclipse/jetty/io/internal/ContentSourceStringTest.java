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

package org.eclipse.jetty.io.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.TestSource;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.fail;

public class ContentSourceStringTest
{
    @Test
    public void testTransientErrorsBecomeTerminalErrors() throws Exception
    {
        TimeoutException originalFailure = new TimeoutException("timeout");
        TestSource originalSource = new TestSource(
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'1'}), false),
            null,
            Content.Chunk.from(originalFailure, false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'2'}), true)
        );

        Promise.Completable<String> promise = new Promise.Completable<>();
        ContentSourceString contentSourceString = new ContentSourceString(originalSource, StandardCharsets.US_ASCII, promise);
        contentSourceString.convert();
        try
        {
            promise.get();
            fail();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), sameInstance(originalFailure));
        }

        Content.Chunk chunk = originalSource.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(chunk.getFailure(), sameInstance(originalFailure));

        originalSource.close();
    }
}
