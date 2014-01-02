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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.junit.Assert;
import org.junit.Test;

public class RstStreamGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        int streamId = 13;
        int streamStatus = StreamStatus.UNSUPPORTED_VERSION.getCode(SPDY.V2);
        RstStreamFrame frame1 = new RstStreamFrame(SPDY.V2, streamId, streamStatus);
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        assertThat("buffer is not null", buffer, not(nullValue()));

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        assertThat("frame2 is not null", frame2, not(nullValue()));
        assertThat("frame2 is type RST_STREAM",ControlFrameType.RST_STREAM, equalTo(frame2.getType()));
        RstStreamFrame rstStream = (RstStreamFrame)frame2;
        assertThat("rstStream version is SPDY.V2",SPDY.V2, equalTo(rstStream.getVersion()));
        assertThat("rstStream id is equal to streamId",streamId, equalTo(rstStream.getStreamId()));
        assertThat("rstStream flags are 0",(byte)0, equalTo(rstStream.getFlags()));
        assertThat("stream status is equal to rstStream statuscode",streamStatus, is(rstStream.getStatusCode()));
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        int streamId = 13;
        int streamStatus = StreamStatus.UNSUPPORTED_VERSION.getCode(SPDY.V2);
        RstStreamFrame frame1 = new RstStreamFrame(SPDY.V2, streamId, streamStatus);
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        while (buffer.hasRemaining())
            parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.RST_STREAM, frame2.getType());
        RstStreamFrame rstStream = (RstStreamFrame)frame2;
        Assert.assertEquals(SPDY.V2, rstStream.getVersion());
        Assert.assertEquals(streamId, rstStream.getStreamId());
        Assert.assertEquals(0, rstStream.getFlags());
        Assert.assertEquals(streamStatus, rstStream.getStatusCode());
    }
}
