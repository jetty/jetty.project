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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.ReplyInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StringDataInfo;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Test;

public class ClosedStreamTest extends AbstractTest
{
    @Test
    public void testSendDataOnHalfClosedStream() throws Exception
    {
        final CountDownLatch replyReceivedLatch = new CountDownLatch(1);
        final CountDownLatch clientReceivedDataLatch = new CountDownLatch(1);

        Session clientSession = startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(true));
                try
                {
                    replyReceivedLatch.await(5,TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                stream.data(new StringDataInfo("data send after half closed",false));
                return null;
            }
        }),new SessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                replyReceivedLatch.countDown();
                stream.reply(new ReplyInfo(true));
                return super.onSyn(stream,synInfo);
            }
        });

        Stream stream = clientSession.syn(new SynInfo(false),new StreamFrameListener.Adapter(){
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                replyReceivedLatch.countDown();
                super.onReply(stream,replyInfo);
            }
            
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                clientReceivedDataLatch.countDown();
                super.onData(stream,dataInfo);
            }
        }).get();
        assertThat("reply has been received by client",replyReceivedLatch.await(5,TimeUnit.SECONDS),is(true));
        assertThat("stream is half closed from server", stream.isHalfClosed(),is(true));
        assertThat("client has not received any data sent after stream was half closed by server",clientReceivedDataLatch.await(150,TimeUnit.MILLISECONDS),is(false));
    }
}
