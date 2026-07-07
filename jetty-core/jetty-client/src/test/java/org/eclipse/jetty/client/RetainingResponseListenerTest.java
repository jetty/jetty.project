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

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RetainingResponseListenerTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testContentIsNotCopiedWithInputStream(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(true, ReadableBuffer.allocate(1, false), callback);
                return true;
            }
        });

        List<ByteBuffer> byteBuffers = new ArrayList<>();
        RetainingResponseListener listener = new RetainingResponseListener()
        {
        };
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContent((r, b) -> byteBuffers.add(b))
            .onResponseContentAsync(listener)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());

        try (InputStream inputStream = listener.takeContentAsInputStream())
        {
            // Modify the content so that we can check if there was a copy.
            assertEquals(1, byteBuffers.size());
            ByteBuffer byteBuffer = byteBuffers.get(0);
            assertEquals(1, byteBuffer.remaining());
            byte modified = 1;
            byteBuffer.put(0, modified);

            // Read from the input stream.
            int read = inputStream.read();
            // If we read the modified value, there was no data copy.
            assertEquals(modified, read);
            // We must be at EOF.
            assertEquals(-1, inputStream.read());
            // Read again at EOF to be sure -1 is returned again.
            assertEquals(-1, inputStream.read());
            // Further getContent() calls see an empty byte[].
            assertEquals(0, listener.getContent().length);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testContentIsCopiedWithInputStream(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(true, ReadableBuffer.allocate(1, false), callback);
                return true;
            }
        });

        List<ByteBuffer> byteBuffers = new ArrayList<>();
        RetainingResponseListener listener = new RetainingResponseListener()
        {
        };
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContent((r, b) -> byteBuffers.add(b))
            .onResponseContentAsync(listener)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());

        try (InputStream inputStream = listener.getContentAsInputStream())
        {
            // Modify the content so that we can check if there was a copy.
            assertEquals(1, byteBuffers.size());
            ByteBuffer byteBuffer = byteBuffers.get(0);
            assertEquals(1, byteBuffer.remaining());
            byte modified = 1;
            byteBuffer.put(0, modified);

            // Read from the input stream.
            int read = inputStream.read();
            // If we read the modified value, there was no data copy.
            assertEquals(0, read);
            // We must be at EOF.
            assertEquals(-1, inputStream.read());
            // Read again at EOF to be sure -1 is returned again.
            assertEquals(-1, inputStream.read());
            // Further getContent() calls see the content's byte[].
            assertEquals(1, listener.getContent().length);
            assertEquals(0, listener.getContent()[0]);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testInputStreamReadAllBytes(Scenario scenario) throws Exception
    {
        byte[] content = new byte[1024 * 1024];
        ThreadLocalRandom.current().nextBytes(content);
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(true, ReadableBuffer.wrap(content), callback);
                return true;
            }
        });

        RetainingResponseListener listener = new RetainingResponseListener()
        {
        };
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContentAsync(listener)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());

        try (InputStream inputStream = listener.takeContentAsInputStream())
        {
            byte[] bytes = inputStream.readAllBytes();

            assertArrayEquals(content, bytes);
            // Further getContent() calls see an empty byte[].
            assertEquals(0, listener.getContent().length);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testInputStreamReadAllBytesFromByteArrayCopy(Scenario scenario) throws Exception
    {
        byte[] content = new byte[1024 * 1024];
        ThreadLocalRandom.current().nextBytes(content);
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(true, ReadableBuffer.wrap(content), callback);
                return true;
            }
        });

        RetainingResponseListener listener = new RetainingResponseListener()
        {
        };
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContentAsync(listener)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());

        InputStream inputStream = listener.getContentAsInputStream();
        byte[] bytes = inputStream.readAllBytes();

        assertArrayEquals(content, bytes);
        // Further getContent() calls see the same byte[].
        assertArrayEquals(content, listener.getContent());
    }
}
