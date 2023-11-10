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

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.fail;

public class HttpClientContentFailuresTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTerminalFailureInContentMakesSendThrow(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        Exception failure = new NumberFormatException();
        TestSource.TestContent content = new TestSource.TestContent(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            Content.Chunk.from(failure, true)
        );

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .method(HttpMethod.POST)
                .body(content)
                .send();
            fail();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), sameInstance(failure));
        }

        Content.Chunk chunk = content.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), sameInstance(failure));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTransientFailureInContentConsideredTerminalAndMakesSendThrow(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        Exception failure = new NumberFormatException();
        TestSource.TestContent content = new TestSource.TestContent(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            Content.Chunk.from(failure, false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{3}), true)
        );

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .method(HttpMethod.POST)
                .body(content)
                .send();
            fail();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), sameInstance(failure));
        }

        Content.Chunk chunk = content.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), sameInstance(failure));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTransientTimeoutFailureMakesSendThrowTimeoutException(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        Exception failure = new TimeoutException();
        TestSource.TestContent content = new TestSource.TestContent(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            Content.Chunk.from(failure, false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{3}), true)
        );

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .method(HttpMethod.POST)
                .body(content)
                .send();
            fail();
        }
        catch (TimeoutException e)
        {
            assertThat(e, sameInstance(failure));
        }

        Content.Chunk chunk = content.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), sameInstance(failure));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testTerminalTimeoutFailureMakesSendThrowTimeoutException(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        Exception failure = new TimeoutException();
        TestSource.TestContent content = new TestSource.TestContent(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            Content.Chunk.from(failure, true)
        );

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .method(HttpMethod.POST)
                .body(content)
                .send();
            fail();
        }
        catch (TimeoutException e)
        {
            assertThat(e, sameInstance(failure));
        }

        Content.Chunk chunk = content.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), sameInstance(failure));
    }
}
