/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StandardByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

public class HeadersGenerateParseTest
{

    private Headers headers = new Headers();
    private int streamId = 13;
    private byte flags = HeadersInfo.FLAG_RESET_COMPRESSION;
    private final TestSPDYParserListener listener = new TestSPDYParserListener();
    private final Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
    private ByteBuffer buffer;

    @Before
    public void setUp()
    {
        parser.addListener(listener);
        headers.put("a", "b");
        buffer = createHeadersFrameBuffer(headers);
    }

    private ByteBuffer createHeadersFrameBuffer(Headers headers)
    {
        HeadersFrame frame1 = new HeadersFrame(SPDY.V2, flags, streamId, headers);
        Generator generator = new Generator(new StandardByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);
        assertThat("Buffer is not null", buffer, notNullValue());
        return buffer;
    }

    @Test
    public void testGenerateParse() throws Exception
    {
        parser.parse(buffer);
        assertExpectationsAreMet(headers);
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        while (buffer.hasRemaining())
            parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));

        assertExpectationsAreMet(headers);
    }

    @Test
    public void testHeadersAreTranslatedToLowerCase()
    {
        Headers headers = new Headers();
        headers.put("Via","localhost");
        parser.parse(createHeadersFrameBuffer(headers));
        HeadersFrame parsedHeadersFrame = assertExpectationsAreMet(headers);
        Headers.Header viaHeader = parsedHeadersFrame.getHeaders().get("via");
        assertThat("Via Header name is lowercase", viaHeader.name(), is("via"));
    }

    private HeadersFrame assertExpectationsAreMet(Headers headers)
    {
        ControlFrame parsedControlFrame = listener.getControlFrame();
        assertThat("listener received controlFrame", parsedControlFrame, notNullValue());
        assertThat("ControlFrame type is HEADERS", ControlFrameType.HEADERS, is(parsedControlFrame.getType()));
        HeadersFrame headersFrame = (HeadersFrame)parsedControlFrame;
        assertThat("Version matches", SPDY.V2, is(headersFrame.getVersion()));
        assertThat("StreamId matches", streamId, is(headersFrame.getStreamId()));
        assertThat("flags match", flags, is(headersFrame.getFlags()));
        assertThat("headers match", headers, is(headersFrame.getHeaders()));
        return headersFrame;
    }
}
