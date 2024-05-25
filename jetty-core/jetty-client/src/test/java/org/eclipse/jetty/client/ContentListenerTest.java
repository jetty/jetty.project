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

import java.net.URI;
import java.nio.ByteBuffer;

import org.eclipse.jetty.client.AsyncContentListenerTest.TestSource;
import org.eclipse.jetty.client.transport.HttpConversation;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.HttpResponse;
import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class ContentListenerTest
{
    @Test
    public void testOnContentThrowingException()
    {
        TestSource originalSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[] {1}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[] {2}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[] {3}), true)
        );
        Response.ContentListener contentListener = (response, content) ->
        {
            throw new NumberFormatException();
        };

        HttpResponse response = new HttpResponse(new HttpRequest(new HttpClient(), new HttpConversation(), URI.create("http://localhost")));
        contentListener.onContentSource(response, originalSource);

        // Assert that the source was failed.
        Content.Chunk lastChunk = originalSource.read();
        assertThat(Content.Chunk.isFailure(lastChunk, true), is(true));
        assertThat(lastChunk.getFailure(), instanceOf(NumberFormatException.class));

        // Assert that the response was aborted.
        assertThat(response.getRequest().getAbortCause(), instanceOf(NumberFormatException.class));
    }
}
