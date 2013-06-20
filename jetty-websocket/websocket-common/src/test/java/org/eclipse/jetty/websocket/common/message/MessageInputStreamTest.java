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

package org.eclipse.jetty.websocket.common.message;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.common.io.LocalWebSocketConnection;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class MessageInputStreamTest
{
    private static final Charset UTF8 = StringUtil.__UTF8_CHARSET;

    @Rule
    public TestName testname = new TestName();

    @Test(timeout=10000)
    public void testBasicAppendRead() throws IOException
    {
        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname);

        try (MessageInputStream stream = new MessageInputStream(conn))
        {
            // Append a message (simple, short)
            ByteBuffer payload = BufferUtil.toBuffer("Hello World",UTF8);
            System.out.printf("payload = %s%n",BufferUtil.toDetailString(payload));
            boolean fin = true;
            stream.appendMessage(payload,fin);

            // Read it from the stream.
            byte buf[] = new byte[32];
            int len = stream.read(buf);
            String message = new String(buf,0,len,UTF8);

            // Test it
            Assert.assertThat("Message",message,is("Hello World"));
        }
    }

    @Test(timeout=10000)
    public void testBlockOnRead() throws IOException
    {
        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname);

        try (MessageInputStream stream = new MessageInputStream(conn))
        {
            final AtomicBoolean hadError = new AtomicBoolean(false);

            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        boolean fin = false;
                        TimeUnit.MILLISECONDS.sleep(200);
                        stream.appendMessage(BufferUtil.toBuffer("Saved",UTF8),fin);
                        TimeUnit.MILLISECONDS.sleep(200);
                        stream.appendMessage(BufferUtil.toBuffer(" by ",UTF8),fin);
                        fin = true;
                        TimeUnit.MILLISECONDS.sleep(200);
                        stream.appendMessage(BufferUtil.toBuffer("Zero",UTF8),fin);
                    }
                    catch (IOException | InterruptedException e)
                    {
                        hadError.set(true);
                        e.printStackTrace(System.err);
                    }
                }
            }).start();

            // Read it from the stream.
            byte buf[] = new byte[32];
            int len = stream.read(buf);
            String message = new String(buf,0,len,UTF8);

            // Test it
            Assert.assertThat("Error when appending",hadError.get(),is(false));
            Assert.assertThat("Message",message,is("Saved by Zero"));
        }
    }

    @Test(timeout=10000)
    public void testBlockOnReadInitial() throws IOException
    {
        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname);

        try (MessageInputStream stream = new MessageInputStream(conn))
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
                        stream.appendMessage(BufferUtil.toBuffer("I will conquer",UTF8),fin);
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
        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname);

        try (MessageInputStream stream = new MessageInputStream(conn))
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
}
