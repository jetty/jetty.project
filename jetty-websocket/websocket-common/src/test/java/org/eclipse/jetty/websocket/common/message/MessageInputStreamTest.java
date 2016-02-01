//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.message;

import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class MessageInputStreamTest
{
    @Rule
    public TestName testname = new TestName();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    @Test(timeout=10000)
    public void testBasicAppendRead() throws IOException
    {
        try (MessageInputStream stream = new MessageInputStream())
        {
            // Append a single message (simple, short)
            ByteBuffer payload = BufferUtil.toBuffer("Hello World",StandardCharsets.UTF_8);
            boolean fin = true;
            stream.appendFrame(payload,fin);

            // Read entire message it from the stream.
            byte buf[] = new byte[32];
            int len = stream.read(buf);
            String message = new String(buf,0,len,StandardCharsets.UTF_8);

            // Test it
            Assert.assertThat("Message",message,is("Hello World"));
        }
    }

    @Test(timeout=5000)
    public void testBlockOnRead() throws Exception
    {
        try (MessageInputStream stream = new MessageInputStream())
        {
            final AtomicBoolean hadError = new AtomicBoolean(false);
            final CountDownLatch startLatch = new CountDownLatch(1);

            // This thread fills the stream (from the "worker" thread)
            // But slowly (intentionally).
            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        startLatch.countDown();
                        boolean fin = false;
                        TimeUnit.MILLISECONDS.sleep(200);
                        stream.appendFrame(BufferUtil.toBuffer("Saved",StandardCharsets.UTF_8),fin);
                        TimeUnit.MILLISECONDS.sleep(200);
                        stream.appendFrame(BufferUtil.toBuffer(" by ",StandardCharsets.UTF_8),fin);
                        fin = true;
                        TimeUnit.MILLISECONDS.sleep(200);
                        stream.appendFrame(BufferUtil.toBuffer("Zero",StandardCharsets.UTF_8),fin);
                    }
                    catch (IOException | InterruptedException e)
                    {
                        hadError.set(true);
                        e.printStackTrace(System.err);
                    }
                }
            }).start();

            // wait for thread to start
            startLatch.await();
            
            // Read it from the stream.
            byte buf[] = new byte[32];
            int len = stream.read(buf);
            String message = new String(buf,0,len,StandardCharsets.UTF_8);

            // Test it
            Assert.assertThat("Error when appending",hadError.get(),is(false));
            Assert.assertThat("Message",message,is("Saved by Zero"));
        }
    }

    @Test(timeout=10000)
    public void testBlockOnReadInitial() throws IOException
    {
        try (MessageInputStream stream = new MessageInputStream())
        {
            final AtomicBoolean hadError = new AtomicBoolean(false);

            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        boolean fin = true;
                        // wait for a little bit before populating buffers
                        TimeUnit.MILLISECONDS.sleep(400);
                        stream.appendFrame(BufferUtil.toBuffer("I will conquer",StandardCharsets.UTF_8),fin);
                    }
                    catch (IOException | InterruptedException e)
                    {
                        hadError.set(true);
                        e.printStackTrace(System.err);
                    }
                }
            }).start();

            // Read byte from stream.
            int b = stream.read();
            // Should be a byte, blocking till byte received.

            // Test it
            Assert.assertThat("Error when appending",hadError.get(),is(false));
            Assert.assertThat("Initial byte",b,is((int)'I'));
        }
    }

    @Test(timeout=10000)
    public void testReadByteNoBuffersClosed() throws IOException
    {
        try (MessageInputStream stream = new MessageInputStream())
        {
            final AtomicBoolean hadError = new AtomicBoolean(false);

            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        // wait for a little bit before sending input closed
                        TimeUnit.MILLISECONDS.sleep(400);
                        stream.messageComplete();
                    }
                    catch (InterruptedException e)
                    {
                        hadError.set(true);
                        e.printStackTrace(System.err);
                    }
                }
            }).start();

            // Read byte from stream.
            int b = stream.read();
            // Should be a -1, indicating the end of the stream.

            // Test it
            Assert.assertThat("Error when appending",hadError.get(),is(false));
            Assert.assertThat("Initial byte",b,is(-1));
        }
    }
    
    @Test(timeout=10000)
    public void testAppendEmptyPayloadRead() throws IOException
    {
        try (MessageInputStream stream = new MessageInputStream())
        {
            // Append parts of message
            ByteBuffer msg1 = BufferUtil.toBuffer("Hello ",StandardCharsets.UTF_8);
            ByteBuffer msg2 = ByteBuffer.allocate(0); // what is being tested
            ByteBuffer msg3 = BufferUtil.toBuffer("World",StandardCharsets.UTF_8);
            
            stream.appendFrame(msg1,false);
            stream.appendFrame(msg2,false);
            stream.appendFrame(msg3,true);

            // Read entire message it from the stream.
            byte buf[] = new byte[32];
            int len = stream.read(buf);
            String message = new String(buf,0,len,StandardCharsets.UTF_8);

            // Test it
            Assert.assertThat("Message",message,is("Hello World"));
        }
    }
    
    @Test(timeout=10000)
    public void testAppendNullPayloadRead() throws IOException
    {
        try (MessageInputStream stream = new MessageInputStream())
        {
            // Append parts of message
            ByteBuffer msg1 = BufferUtil.toBuffer("Hello ",StandardCharsets.UTF_8);
            ByteBuffer msg2 = null; // what is being tested
            ByteBuffer msg3 = BufferUtil.toBuffer("World",StandardCharsets.UTF_8);
            
            stream.appendFrame(msg1,false);
            stream.appendFrame(msg2,false);
            stream.appendFrame(msg3,true);

            // Read entire message it from the stream.
            byte buf[] = new byte[32];
            int len = stream.read(buf);
            String message = new String(buf,0,len,StandardCharsets.UTF_8);

            // Test it
            Assert.assertThat("Message",message,is("Hello World"));
        }
    }
}
