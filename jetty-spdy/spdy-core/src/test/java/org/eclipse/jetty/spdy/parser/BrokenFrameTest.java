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

package org.eclipse.jetty.spdy.parser;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipException;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SynStreamFrame;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Fields;
import org.junit.Test;

public class BrokenFrameTest
{

    @Test
    public void testInvalidHeaderNameLength() throws Exception
    {
        Fields headers = new Fields();
        headers.add("broken", "header");
        SynStreamFrame frame = new SynStreamFrame(SPDY.V2, SynInfo.FLAG_CLOSE, 1, 0, (byte)0, (short)0, headers);
        Generator generator = new Generator(new MappedByteBufferPool(), new NoCompressionCompressionFactory.NoCompressionCompressor());

        ByteBuffer bufferWithBrokenHeaderNameLength = generator.control(frame);
        // Break the header name length to provoke the Parser to throw a StreamException
        bufferWithBrokenHeaderNameLength.put(21, (byte)0);

        ByteBuffer bufferWithValidSynStreamFrame = generator.control(frame);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(BufferUtil.toArray(bufferWithBrokenHeaderNameLength));
        outputStream.write(BufferUtil.toArray(bufferWithValidSynStreamFrame));

        byte concatenatedFramesByteArray[] = outputStream.toByteArray();
        ByteBuffer concatenatedBuffer = BufferUtil.toBuffer(concatenatedFramesByteArray);

        final CountDownLatch latch = new CountDownLatch(2);
        Parser parser = new Parser(new NoCompressionCompressionFactory.NoCompressionDecompressor());
        parser.addListener(new Parser.Listener.Adapter()
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
                latch.countDown();
            }

            @Override
            public void onStreamException(StreamException x)
            {
                latch.countDown();
            }
        });
        parser.parse(concatenatedBuffer);

        assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testInvalidVersion() throws Exception
    {
        Fields headers = new Fields();
        headers.add("good", "header");
        headers.add("another","header");
        SynStreamFrame frame = new SynStreamFrame(SPDY.V2, SynInfo.FLAG_CLOSE, 1, 0, (byte)0, (short)0, headers);
        Generator generator = new Generator(new MappedByteBufferPool(), new NoCompressionCompressionFactory.NoCompressionCompressor());

        ByteBuffer bufferWithBrokenVersion = generator.control(frame);
        // Break the header name length to provoke the Parser to throw a StreamException
        bufferWithBrokenVersion.put(1, (byte)4);

        ByteBuffer bufferWithValidSynStreamFrame = generator.control(frame);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(BufferUtil.toArray(bufferWithBrokenVersion));
        outputStream.write(BufferUtil.toArray(bufferWithValidSynStreamFrame));

        byte concatenatedFramesByteArray[] = outputStream.toByteArray();
        ByteBuffer concatenatedBuffer = BufferUtil.toBuffer(concatenatedFramesByteArray);

        final CountDownLatch latch = new CountDownLatch(2);
        Parser parser = new Parser(new NoCompressionCompressionFactory.NoCompressionDecompressor());
        parser.addListener(new Parser.Listener.Adapter()
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
                latch.countDown();
            }

            @Override
            public void onStreamException(StreamException x)
            {
                latch.countDown();
            }
        });
        parser.parse(concatenatedBuffer);

        assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testInvalidVersionWithSplitBuffer() throws Exception
    {
        Fields headers = new Fields();
        headers.add("good", "header");
        headers.add("another","header");
        SynStreamFrame frame = new SynStreamFrame(SPDY.V2, SynInfo.FLAG_CLOSE, 1, 0, (byte)0, (short)0, headers);
        Generator generator = new Generator(new MappedByteBufferPool(), new NoCompressionCompressionFactory.NoCompressionCompressor());

        ByteBuffer bufferWithBrokenVersion = generator.control(frame);
        // Break the header name length to provoke the Parser to throw a StreamException
        bufferWithBrokenVersion.put(1, (byte)4);

        ByteBuffer bufferWithValidSynStreamFrame = generator.control(frame);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(BufferUtil.toArray(bufferWithBrokenVersion));
        outputStream.write(BufferUtil.toArray(bufferWithValidSynStreamFrame));

        byte concatenatedFramesByteArray[] = outputStream.toByteArray();
        ByteBuffer concatenatedBuffer1 = BufferUtil.toBuffer(Arrays.copyOfRange(concatenatedFramesByteArray,0,20));
        ByteBuffer concatenatedBuffer2 = BufferUtil.toBuffer(Arrays.copyOfRange(concatenatedFramesByteArray,20,
                concatenatedFramesByteArray.length));

        final CountDownLatch latch = new CountDownLatch(2);
        Parser parser = new Parser(new NoCompressionCompressionFactory.NoCompressionDecompressor());
        parser.addListener(new Parser.Listener.Adapter()
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
                latch.countDown();
            }

            @Override
            public void onStreamException(StreamException x)
            {
                latch.countDown();
            }
        });
        parser.parse(concatenatedBuffer1);
        parser.parse(concatenatedBuffer2);

        assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testInvalidVersionAndGoodFrameSplitInThreeBuffers() throws Exception
    {
        Fields headers = new Fields();
        headers.add("good", "header");
        headers.add("another","header");
        SynStreamFrame frame = new SynStreamFrame(SPDY.V2, SynInfo.FLAG_CLOSE, 1, 0, (byte)0, (short)0, headers);
        Generator generator = new Generator(new MappedByteBufferPool(), new NoCompressionCompressionFactory.NoCompressionCompressor());

        ByteBuffer bufferWithBrokenVersion = generator.control(frame);
        // Break the header name length to provoke the Parser to throw a StreamException
        bufferWithBrokenVersion.put(1, (byte)4);

        ByteBuffer bufferWithValidSynStreamFrame = generator.control(frame);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(BufferUtil.toArray(bufferWithBrokenVersion));
        outputStream.write(BufferUtil.toArray(bufferWithValidSynStreamFrame));

        byte concatenatedFramesByteArray[] = outputStream.toByteArray();
        ByteBuffer concatenatedBuffer1 = BufferUtil.toBuffer(Arrays.copyOfRange(concatenatedFramesByteArray,0,20));
        ByteBuffer concatenatedBuffer2 = BufferUtil.toBuffer(Arrays.copyOfRange(concatenatedFramesByteArray,20, 30));
        ByteBuffer concatenatedBuffer3 = BufferUtil.toBuffer(Arrays.copyOfRange(concatenatedFramesByteArray,30,
                concatenatedFramesByteArray.length));

        final CountDownLatch latch = new CountDownLatch(2);
        Parser parser = new Parser(new NoCompressionCompressionFactory.NoCompressionDecompressor());
        parser.addListener(new Parser.Listener.Adapter()
        {
            @Override
            public void onControlFrame(ControlFrame frame)
            {
                latch.countDown();
            }

            @Override
            public void onStreamException(StreamException x)
            {
                latch.countDown();
            }
        });
        parser.parse(concatenatedBuffer1);
        parser.parse(concatenatedBuffer2);
        parser.parse(concatenatedBuffer3);

        assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
    }

    private static class NoCompressionCompressionFactory implements CompressionFactory
    {

        @Override
        public Compressor newCompressor()
        {
            return null;
        }

        @Override
        public Decompressor newDecompressor()
        {
            return null;
        }

        public static class NoCompressionCompressor implements Compressor
        {

            private byte[] input;

            @Override
            public void setInput(byte[] input)
            {
                this.input = input;
            }

            @Override
            public void setDictionary(byte[] dictionary)
            {
            }

            @Override
            public int compress(byte[] output)
            {
                System.arraycopy(input, 0, output, 0, input.length);
                return input.length;
            }
        }

        public static class NoCompressionDecompressor implements Decompressor
        {
            private byte[] input;

            @Override
            public void setDictionary(byte[] dictionary)
            {
            }

            @Override
            public void setInput(byte[] input)
            {
                this.input = input;
            }

            @Override
            public int decompress(byte[] output) throws ZipException
            {
                System.arraycopy(input, 0, output, 0, input.length);
                return input.length;
            }
        }
    }
}
