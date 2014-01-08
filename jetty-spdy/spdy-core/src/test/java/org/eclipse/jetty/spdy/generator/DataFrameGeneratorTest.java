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

package org.eclipse.jetty.spdy.generator;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.spdy.api.ByteBufferDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.frames.DataFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DataFrameGeneratorTest
{
    private int increment = 1024;
    private int streamId = 1;
    private ArrayByteBufferPool bufferPool;
    private DataFrameGenerator dataFrameGenerator;
    private ByteBuffer headerBuffer = ByteBuffer.allocate(DataFrame.HEADER_LENGTH);

    @Before
    public void setUp()
    {
        bufferPool = new ArrayByteBufferPool(64, increment, 8192);
        dataFrameGenerator = new DataFrameGenerator(bufferPool);
        headerBuffer.putInt(0, streamId & 0x7F_FF_FF_FF);

    }

    @Test
    public void testGenerateSmallFrame()
    {
        int bufferSize = 256;
        generateFrame(bufferSize);
    }

    @Test
    public void testGenerateFrameWithBufferThatEqualsBucketSize()
    {
        int bufferSize = increment;
        generateFrame(bufferSize);
    }

    @Test
    public void testGenerateFrameWithBufferThatEqualsBucketSizeMinusHeaderLength()
    {
        int bufferSize = increment - DataFrame.HEADER_LENGTH;
        generateFrame(bufferSize);
    }

    private void generateFrame(int bufferSize)
    {
        ByteBuffer byteBuffer = createByteBuffer(bufferSize);
        ByteBufferDataInfo dataInfo = new ByteBufferDataInfo(byteBuffer, true);
        fillHeaderBuffer(bufferSize);
        ByteBuffer dataFrameBuffer = dataFrameGenerator.generate(streamId, bufferSize, dataInfo);

        assertThat("The content size in dataFrameBuffer matches the buffersize + header length",
                dataFrameBuffer.limit(),
                is(bufferSize + DataFrame.HEADER_LENGTH));

        byte[] headerBytes = new byte[DataFrame.HEADER_LENGTH];
        dataFrameBuffer.get(headerBytes, 0, DataFrame.HEADER_LENGTH);
        
        assertThat("Header bytes are prepended", headerBytes, is(headerBuffer.array()));
    }

    private ByteBuffer createByteBuffer(int bufferSize)
    {
        byte[] bytes = new byte[bufferSize];
        ThreadLocalRandom.current().nextBytes(bytes);
        ByteBuffer byteBuffer = bufferPool.acquire(bufferSize, false);
        BufferUtil.flipToFill(byteBuffer);
        byteBuffer.put(bytes);
        BufferUtil.flipToFlush(byteBuffer, 0);
        return byteBuffer;
    }

    private void fillHeaderBuffer(int bufferSize)
    {
        headerBuffer.putInt(4, bufferSize & 0x00_FF_FF_FF);
        headerBuffer.put(4, DataInfo.FLAG_CLOSE);
    }

}
