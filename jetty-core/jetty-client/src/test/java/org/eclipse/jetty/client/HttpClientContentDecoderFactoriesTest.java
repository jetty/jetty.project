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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class HttpClientContentDecoderFactoriesTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testContentDecoderReturningEmptyRetainableDecodedBuffer(Scenario scenario) throws Exception
    {
        ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().add(HttpHeader.CONTENT_ENCODING, "UPPERCASE");
                response.write(true, ByteBuffer.wrap("**THE ANSWER IS FORTY TWO**".getBytes(US_ASCII)), callback);
                return true;
            }
        });

        client.getContentDecoderFactories().put(new ContentDecoder.Factory("UPPERCASE")
        {
            @Override
            public ContentDecoder newContentDecoder()
            {
                return byteBuffer ->
                {
                    byte b = byteBuffer.get();
                    if (b == '*')
                        return bufferPool.acquire(0, true);

                    RetainableByteBuffer buffer = bufferPool.acquire(1, true);
                    int pos = BufferUtil.flipToFill(buffer.getByteBuffer());
                    buffer.getByteBuffer().put(StringUtil.asciiToLowerCase(b));
                    BufferUtil.flipToFlush(buffer.getByteBuffer(), pos);
                    return buffer;
                };
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .send();
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("the answer is forty two"));

        assertThat("Decoder leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testContentDecoderReturningNonRetainableDecodedBuffer(Scenario scenario) throws Exception
    {
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().add(HttpHeader.CONTENT_ENCODING, "UPPERCASE");
                response.write(true, ByteBuffer.wrap("THE ANSWER IS FORTY TWO".getBytes(US_ASCII)), callback);
                return true;
            }
        });

        client.getContentDecoderFactories().put(new ContentDecoder.Factory("UPPERCASE")
        {
            @Override
            public ContentDecoder newContentDecoder()
            {
                return byteBuffer ->
                {
                    String uppercase = US_ASCII.decode(byteBuffer).toString();
                    String lowercase = StringUtil.asciiToLowerCase(uppercase);
                    return RetainableByteBuffer.wrap(ByteBuffer.wrap(lowercase.getBytes(US_ASCII)));
                };
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .send();
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("the answer is forty two"));
    }
}
