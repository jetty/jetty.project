//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.BufferingFlowControlStrategy;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.Assert;
import org.junit.Test;

public class BufferingFlowControlStrategyTest extends FlowControlStrategyTest
{
    @Override
    protected FlowControlStrategy newFlowControlStrategy()
    {
        return new BufferingFlowControlStrategy(0.5F);
    }

    @Test
    public void testFlowControlWhenServerResetsStream() throws Exception
    {
        // On server, we don't consume the data and we immediately reset.
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.Adapter.INSTANCE);
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("POST", new HttpFields());
        HeadersFrame frame = new HeadersFrame(0, metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        final CountDownLatch resetLatch = new CountDownLatch(1);
        session.newStream(frame, streamPromise, new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        // Perform a big upload that will stall the flow control windows.
        ByteBuffer data = ByteBuffer.allocate(5 * FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), data, true), new Callback.Adapter()
        {
            @Override
            public void failed(Throwable x)
            {
                dataLatch.countDown();
            }
        });

        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        // Wait a little more for the window updates to be processed.
        Thread.sleep(1000);

        // We sent 65535 data bytes to the server (the max allowed by the send window).
        // The ratio being 50%, means that the regulator has a limit at 32767 bytes.
        // This will be TCP written as:
        // (9 + HEADERS) + 3 * (9 + DATA (16384)) + (9 + DATA (16383))
        // since 16384 is the max frame length.
        // One window update will be sent back of 32768 bytes.
        // It does not matter how the TCP reads read the frames, since eventually
        // the server will read 2 * 16384 data bytes (perhaps in multiple "fake"
        // data frames).
        // When 32768 bytes will be read and consumed, a window update will be
        // sent to the client; the remaining 32767 will be read and consumed,
        // but the server won't send the window update (it's exactly equal to
        // the regulator limit), and the client won't send more bytes because
        // the send has been reset.
        // So the client send window must be 32768.

        HTTP2Session http2Session = (HTTP2Session)session;
        Assert.assertEquals(Frame.DEFAULT_MAX_LENGTH * 2, http2Session.getSendWindow());
    }
}
