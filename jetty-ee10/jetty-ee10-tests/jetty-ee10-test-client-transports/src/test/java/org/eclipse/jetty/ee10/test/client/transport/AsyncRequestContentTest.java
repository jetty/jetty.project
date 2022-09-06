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

package org.eclipse.jetty.ee10.test.client.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.client.util.OutputStreamRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncRequestContentTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testEmptyAsyncContent(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new ConsumeInputHandler());

        AsyncRequestContent content = new AsyncRequestContent();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.isSucceeded() &&
                    result.getResponse().getStatus() == HttpStatus.OK_200)
                    latch.countDown();
            });
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testAsyncContent(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new ConsumeInputHandler());

        AsyncRequestContent content = new AsyncRequestContent();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.isSucceeded() &&
                    result.getResponse().getStatus() == HttpStatus.OK_200)
                    latch.countDown();
            });
        content.offer(ByteBuffer.wrap(new byte[1]));
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testEmptyInputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new ConsumeInputHandler());

        InputStreamRequestContent content =
            new InputStreamRequestContent(new ByteArrayInputStream(new byte[0]));
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.isSucceeded() &&
                    result.getResponse().getStatus() == HttpStatus.OK_200)
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new ConsumeInputHandler());

        InputStreamRequestContent content =
            new InputStreamRequestContent(new ByteArrayInputStream(new byte[1]));
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.isSucceeded() &&
                    result.getResponse().getStatus() == HttpStatus.OK_200)
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testEmptyOutputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new ConsumeInputHandler());

        OutputStreamRequestContent content = new OutputStreamRequestContent();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.isSucceeded() &&
                    result.getResponse().getStatus() == HttpStatus.OK_200)
                    latch.countDown();
            });
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testOutputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new ConsumeInputHandler());

        OutputStreamRequestContent content = new OutputStreamRequestContent();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.POST(scenario.newURI())
            .body(content)
            .send(result ->
            {
                if (result.isSucceeded() &&
                    result.getResponse().getStatus() == HttpStatus.OK_200)
                    latch.countDown();
            });
        OutputStream output = content.getOutputStream();
        output.write(new byte[1]);
        output.flush();
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBufferReuseAfterCallbackCompleted(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new ConsumeInputHandler());

        AsyncRequestContent content = new AsyncRequestContent();

        CountDownLatch latch = new CountDownLatch(1);
        List<Byte> requestContent = new ArrayList<>();
        scenario.client.POST(scenario.newURI())
            .onRequestContent(((request, buffer) -> requestContent.add(buffer.get())))
            .body(content)
            .send(result ->
            {
                if (result.isSucceeded() &&
                    result.getResponse().getStatus() == HttpStatus.OK_200)
                    latch.countDown();
            });

        byte first = '1';
        byte second = '2';
        byte[] bytes = new byte[1];
        bytes[0] = first;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        content.offer(buffer, Callback.from(() ->
        {
            bytes[0] = second;
            content.offer(ByteBuffer.wrap(bytes), Callback.from(content::close));
        }));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(2, requestContent.size());
        assertEquals(first, requestContent.get(0));
        assertEquals(second, requestContent.get(1));
    }

    private static class ConsumeInputHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            ServletInputStream input = request.getInputStream();
            while (true)
            {
                int read = input.read();
                if (read < 0)
                    break;
            }
            response.setStatus(HttpStatus.OK_200);
        }
    }
}
