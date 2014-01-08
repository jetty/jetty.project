//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.spdy.frames;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.HeadersInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.Fields;
import org.junit.Before;
import org.junit.Test;

public class HeadersGenerateParseTest
{

    private Fields headers = new Fields();
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

    private ByteBuffer createHeadersFrameBuffer(Fields headers)
    {
        HeadersFrame frame1 = new HeadersFrame(SPDY.V2, flags, streamId, headers);
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
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
        Fields headers = new Fields();
        headers.put("Via","localhost");
        parser.parse(createHeadersFrameBuffer(headers));
        HeadersFrame parsedHeadersFrame = assertExpectationsAreMet(headers);
        Fields.Field viaHeader = parsedHeadersFrame.getHeaders().get("via");
        assertThat("Via Header name is lowercase", viaHeader.getName(), is("via"));
    }

    private HeadersFrame assertExpectationsAreMet(Fields headers)
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
