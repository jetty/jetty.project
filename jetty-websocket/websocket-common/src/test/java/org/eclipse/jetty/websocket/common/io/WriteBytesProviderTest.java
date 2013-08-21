//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.io;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Hex;
import org.eclipse.jetty.websocket.common.UnitGenerator;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.junit.Assert;
import org.junit.Test;

public class WriteBytesProviderTest
{
    private static final Logger LOG = Log.getLogger(WriteBytesProviderTest.class);
    private WriteBytesProvider bytesProvider;

    private void assertCallbackSuccessCount(TrackingCallback callback, int expectedSuccsesCount)
    {
        Assert.assertThat("Callback was called",callback.isCalled(),is(true));
        Assert.assertThat("No Failed Callbacks",callback.getFailure(),nullValue());
        Assert.assertThat("# of Success Callbacks",callback.getCallCount(),is(expectedSuccsesCount));
    }

    @Test
    public void testSingleFrame()
    {
        UnitGenerator generator = new UnitGenerator();
        TrackingCallback flushCallback = new TrackingCallback();
        bytesProvider = new WriteBytesProvider(generator,flushCallback);

        TrackingCallback frameCallback = new TrackingCallback();
        Frame frame = new TextFrame().setPayload("Test");

        // place in to bytes provider
        bytesProvider.enqueue(frame,frameCallback);

        // get bytes out
        List<ByteBuffer> bytes = bytesProvider.getByteBuffers();
        Assert.assertThat("Number of buffers",bytes.size(),is(2));

        // Test byte values
        assertExpectedBytes(bytes,"810454657374");

        // Trigger success
        bytesProvider.succeeded();

        // Validate success
        assertCallbackSuccessCount(flushCallback,1);
        assertCallbackSuccessCount(frameCallback,1);
    }

    @Test
    public void testTextClose()
    {
        UnitGenerator generator = new UnitGenerator();
        TrackingCallback flushCallback = new TrackingCallback();
        bytesProvider = new WriteBytesProvider(generator,flushCallback);

        // Create frames for provider
        TrackingCallback textCallback = new TrackingCallback();
        TrackingCallback closeCallback = new TrackingCallback();
        bytesProvider.enqueue(new TextFrame().setPayload("Bye"),textCallback);
        bytesProvider.enqueue(new CloseInfo().asFrame(),closeCallback);

        // get bytes out
        List<ByteBuffer> bytes = bytesProvider.getByteBuffers();
        Assert.assertThat("Number of buffers",bytes.size(),is(4));

        // Test byte values
        StringBuilder expected = new StringBuilder();
        expected.append("8103427965"); // text frame
        expected.append("8800"); // (empty) close frame
        assertExpectedBytes(bytes,expected.toString());

        // Trigger success
        bytesProvider.succeeded();

        // Validate success
        assertCallbackSuccessCount(flushCallback,1);
        assertCallbackSuccessCount(textCallback,1);
        assertCallbackSuccessCount(closeCallback,1);
    }

    @Test
    public void testTinyBufferSizeFrame()
    {
        UnitGenerator generator = new UnitGenerator();
        TrackingCallback flushCallback = new TrackingCallback();
        bytesProvider = new WriteBytesProvider(generator,flushCallback);
        int bufferSize = 30;
        bytesProvider.setBufferSize(bufferSize);

        // Create frames for provider
        TrackingCallback binCallback = new TrackingCallback();
        TrackingCallback closeCallback = new TrackingCallback();
        int binPayloadSize = 50;
        byte bin[] = new byte[binPayloadSize];
        Arrays.fill(bin,(byte)0x00);
        BinaryFrame binFrame = new BinaryFrame().setPayload(bin);
        byte maskingKey[] = Hex.asByteArray("11223344");
        binFrame.setMask(maskingKey);
        bytesProvider.enqueue(binFrame,binCallback);
        bytesProvider.enqueue(new CloseInfo().asFrame(),closeCallback);

        // get bytes out
        List<ByteBuffer> bytes = bytesProvider.getByteBuffers();
        Assert.assertThat("Number of buffers",bytes.size(),is(5));
        assertBufferLengths(bytes,6,bufferSize,binPayloadSize-bufferSize,2,0);

        // Test byte values
        StringBuilder expected = new StringBuilder();
        expected.append("82B2").append("11223344"); // bin frame
        // build up masked bytes
        byte masked[] = new byte[binPayloadSize];
        Arrays.fill(masked,(byte)0x00);
        for (int i = 0; i < binPayloadSize; i++)
        {
            masked[i] ^= maskingKey[i % 4];
        }
        expected.append(Hex.asHex(masked));
        expected.append("8800"); // (empty) close frame
        assertExpectedBytes(bytes,expected.toString());

        // Trigger success
        bytesProvider.succeeded();

        // Validate success
        assertCallbackSuccessCount(flushCallback,1);
        assertCallbackSuccessCount(binCallback,1);
        assertCallbackSuccessCount(closeCallback,1);
    }

    private void assertBufferLengths(List<ByteBuffer> bytes, int... expectedLengths)
    {
        for (int i = 0; i < expectedLengths.length; i++)
        {
            Assert.assertThat("Buffer[" + i + "].remaining",bytes.get(i).remaining(),is(expectedLengths[i]));
        }
    }

    private void assertExpectedBytes(List<ByteBuffer> bytes, String expected)
    {
        String actual = gatheredHex(bytes);
        Assert.assertThat("Expected Bytes",actual,is(expected));
    }

    private String gatheredHex(List<ByteBuffer> bytes)
    {
        int len = 0;
        for (ByteBuffer buf : bytes)
        {
            LOG.debug("buffer[] {}", BufferUtil.toDetailString(buf));
            len += buf.remaining();
        }
        len = len * 2;
        StringBuilder ret = new StringBuilder(len);
        for (ByteBuffer buf : bytes)
        {
            ret.append(Hex.asHex(buf));
        }
        return ret.toString();
    }
}
