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

package org.eclipse.jetty.spdy.api;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.spdy.StandardSession;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class ClientUsageTest
{
    @Test
    public void testClientRequestResponseNoBody() throws Exception
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, null, null, null);

        session.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // Do something with the response
                replyInfo.getHeaders().get("host");

                // Then issue another similar request
                try
                {
                    stream.getSession().syn(new SynInfo(new Fields(), true), this);
                }
                catch (ExecutionException | InterruptedException | TimeoutException e)
                {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    @Test
    public void testClientReceivesPush1() throws InterruptedException, ExecutionException, TimeoutException
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, null, null, null);

        session.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
        {
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                    }
                };
            };

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // Do something with the response
                replyInfo.getHeaders().get("host");

                // Then issue another similar request
                try
                {
                    stream.getSession().syn(new SynInfo(new Fields(), true), this);
                }
                catch (ExecutionException | InterruptedException | TimeoutException e)
                {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    @Test
    public void testClientReceivesPush2() throws InterruptedException, ExecutionException, TimeoutException
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, new SessionFrameListener.Adapter()
        {
            public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
            {
                return new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                    }
                };
            }
        }, null, null);

        session.syn(new SynInfo(new Fields(), true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // Do something with the response
                replyInfo.getHeaders().get("host");

                // Then issue another similar request
                try
                {
                    stream.getSession().syn(new SynInfo(new Fields(), true), this);
                }
                catch (ExecutionException | InterruptedException | TimeoutException e)
                {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    @Test
    public void testClientRequestWithBodyResponseNoBody() throws Exception
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, null, null, null);

        Stream stream = session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), false, (byte)0),
                new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onReply(Stream stream, ReplyInfo replyInfo)
                    {
                        // Do something with the response
                        replyInfo.getHeaders().get("host");

                        // Then issue another similar request
                        try
                        {
                            stream.getSession().syn(new SynInfo(new Fields(), true), this);
                        }
                        catch (ExecutionException | InterruptedException | TimeoutException e)
                        {
                            throw new IllegalStateException(e);
                        }
                    }
                });
        // Send-and-forget the data
        stream.data(new StringDataInfo("data", true));
    }

    @Test
    public void testAsyncClientRequestWithBodyResponseNoBody() throws Exception
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, null, null, null);

        final String context = "context";
        session.syn(new SynInfo(new Fields(), false), new StreamFrameListener.Adapter()
                {
                    @Override
                    public void onReply(Stream stream, ReplyInfo replyInfo)
                    {
                        // Do something with the response
                        replyInfo.getHeaders().get("host");

                        // Then issue another similar request
                        try
                        {
                            stream.getSession().syn(new SynInfo(new Fields(), true), this);
                        }
                        catch (ExecutionException | InterruptedException | TimeoutException e)
                        {
                            throw new IllegalStateException(e);
                        }
                    }
                }, new Promise.Adapter<Stream>()
                {
                    @Override
                    public void succeeded(Stream stream)
                    {
                        // Differently from JDK 7 AIO, there is no need to
                        // have an explicit parameter for the context since
                        // that is captured while the handler is created anyway,
                        // and it is used only by the handler as parameter

                        // The style below is fire-and-forget, since
                        // we do not pass the handler nor we call get()
                        // to wait for the data to be sent
                        stream.data(new StringDataInfo(context, true), new Callback.Adapter());
                    }
                }
        );
    }

    @Test
    public void testAsyncClientRequestWithBodyAndResponseWithBody() throws Exception
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, null, null, null);

        session.syn(new SynInfo(new Fields(), false), new StreamFrameListener.Adapter()
                {
                    // The good of passing the listener to push() is that applications can safely
                    // accumulate info from the reply headers to be used in the data callback,
                    // e.g. content-type, charset, etc.

                    @Override
                    public void onReply(Stream stream, ReplyInfo replyInfo)
                    {
                        // Do something with the response
                        Fields headers = replyInfo.getHeaders();
                        int contentLength = headers.get("content-length").getValueAsInt();
                        stream.setAttribute("content-length", contentLength);
                        if (!replyInfo.isClose())
                            stream.setAttribute("builder", new StringBuilder());

                        // May issue another similar request while waiting for data
                        try
                        {
                            stream.getSession().syn(new SynInfo(new Fields(), true), this);
                        }
                        catch (ExecutionException | InterruptedException | TimeoutException e)
                        {
                            throw new IllegalStateException(e);
                        }
                    }

                    @Override
                    public void onData(Stream stream, DataInfo dataInfo)
                    {
                        StringBuilder builder = (StringBuilder)stream.getAttribute("builder");
                        builder.append(dataInfo.asString(StandardCharsets.UTF_8, true));

                    }
                }, new Promise.Adapter<Stream>()
                {
                    @Override
                    public void succeeded(Stream stream)
                    {
                        stream.data(new BytesDataInfo("wee".getBytes(StandardCharsets.UTF_8), false), new Callback.Adapter());
                        stream.data(new StringDataInfo("foo", false), new Callback.Adapter());
                        stream.data(new ByteBufferDataInfo(StandardCharsets.UTF_8.encode("bar"), true), new Callback.Adapter());
                    }
                }
        );
    }
}
