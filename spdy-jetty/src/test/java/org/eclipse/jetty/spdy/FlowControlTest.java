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

package org.eclipse.jetty.spdy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.spdy.api.BytesDataInfo;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class FlowControlTest extends AbstractTest
{
    @Test
    public void testServerFlowControlOneBigWrite() throws Exception
    {
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false));
                stream.data(new BytesDataInfo(new byte[128 * 1024], true));
                return null;
            }
        }), null);

        final AtomicInteger bytes = new AtomicInteger();
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                bytes.addAndGet(dataInfo.getBytesCount());
                if (dataInfo.isClose())
                    dataLatch.countDown();
            }
        });

        Assert.assertTrue(dataLatch.await(500, TimeUnit.SECONDS));
    }

    @Test
    public void testServerFlowControlTwoBigWrites() throws Exception
    {
        Session session = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false));
                stream.data(new BytesDataInfo(new byte[128 * 1024], false));
                stream.data(new BytesDataInfo(new byte[128 * 1024], true));
                return null;
            }
        }), null);

        final AtomicInteger bytes = new AtomicInteger();
        final CountDownLatch dataLatch = new CountDownLatch(1);
        session.syn(SPDY.V2, new SynInfo(true), new Stream.FrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                bytes.addAndGet(dataInfo.getBytesCount());
                if (dataInfo.isClose())
                    dataLatch.countDown();
            }
        });

        Assert.assertTrue(dataLatch.await(500, TimeUnit.SECONDS));
    }
}
