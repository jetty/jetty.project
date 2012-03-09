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

package org.eclipse.jetty.spdy.api;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class ServerUsageTest
{
    @Test
    public void testServerSynAndReplyWithData() throws Exception
    {
        new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo streamInfo)
            {
                Headers synHeaders = streamInfo.getHeaders();
                // Do something with headers, for example extract them and
                // perform an http request via Jetty's LocalConnector

                // Get the http response, fill headers and data
                Headers replyHeaders = new Headers();
                replyHeaders.put(synHeaders.get("host"));
                // Sends a reply
                stream.reply(new ReplyInfo(replyHeaders, false));

                // Sends data
                StringDataInfo dataInfo = new StringDataInfo("foo", false);
                stream.data(dataInfo);
                // Stream is now closed
                return null;
            }
        };
    }

    @Test
    public void testServerInitiatesStreamAndPushesData() throws Exception
    {
        new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onConnect(Session session)
            {
                // SPDY does not allow the server to initiate a stream without an existing stream
                // being opened by the client already.
                // Correct SPDY sequence will be:
                // C ---       SYN_STREAM(id=1)       --> S
                // C <--       SYN_REPLY(id=1)        --- S
                // C <-- SYN_STREAM(id=2,uni,assId=1) --- S
                //
                // However, the API may allow to initiate the stream

                session.syn(new SynInfo(false), null, 0, TimeUnit.MILLISECONDS, new Handler.Adapter<Stream>()
                {
                    @Override
                    public void completed(Stream stream)
                    {
                        // The point here is that we have no idea if the client accepted our stream
                        // So we return a stream, we may be able to send the headers frame, but later
                        // the client sends a rst frame.
                        // We have to atomically set some flag on the stream to signal it's closed
                        // and any operation on it will throw
                        stream.headers(new HeadersInfo(new Headers(), true));
                    }
                });
            }
        };
    }

    @Test
    public void testServerPush() throws Exception
    {
        new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo streamInfo)
            {
                // Need to send the reply first
                stream.reply(new ReplyInfo(false));

                Session session = stream.getSession();
                // Since it's unidirectional, no need to pass the listener
                session.syn(new SynInfo(new Headers(), false, true, stream.getId(), (byte)0), null, 0, TimeUnit.MILLISECONDS, new Handler.Adapter<Stream>()
                {
                    @Override
                    public void completed(Stream pushStream)
                    {
                        pushStream.data(new StringDataInfo("foo", false));
                    }
                });
                return null;
            }
        };
    }
}
