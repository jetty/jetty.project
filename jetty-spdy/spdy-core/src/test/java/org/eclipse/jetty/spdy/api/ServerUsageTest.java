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

import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class ServerUsageTest
{
    @Test
    public void testServerSynAndReplyWithData() throws Exception
    {
        ServerSessionFrameListener ssfl = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo streamInfo)
            {
                Fields synHeaders = streamInfo.getHeaders();
                // Do something with headers, for example extract them and
                // perform an http request via Jetty's LocalConnector

                // Get the http response, fill headers and data
                Fields replyHeaders = new Fields();
                replyHeaders.put(synHeaders.get("host"));
                // Sends a reply
                stream.reply(new ReplyInfo(replyHeaders, false), new Callback.Adapter());

                // Sends data
                StringDataInfo dataInfo = new StringDataInfo("foo", false);
                stream.data(dataInfo, new Callback.Adapter());
                // Stream is now closed
                return null;
            }
        };
        Assert.assertNotNull(ssfl);
    }

    @Test
    public void testServerInitiatesStreamAndPushesData() throws Exception
    {
        ServerSessionFrameListener ssfl = new ServerSessionFrameListener.Adapter()
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

                session.syn(new SynInfo(new Fields(), false), null, new Promise.Adapter<Stream>()
                {
                    @Override
                    public void succeeded(Stream stream)
                    {
                        // The point here is that we have no idea if the client accepted our stream
                        // So we return a stream, we may be able to send the headers frame, but later
                        // the client sends a rst frame.
                        // We have to atomically set some flag on the stream to signal it's closed
                        // and any operation on it will throw
                        stream.headers(new HeadersInfo(new Fields(), true), new Callback.Adapter());
                    }
                });
            }
        };
        Assert.assertNotNull(ssfl);
    }

    @Test
    public void testServerPush() throws Exception
    {
        ServerSessionFrameListener ssfl = new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo streamInfo)
            {
                // Need to send the reply first
                stream.reply(new ReplyInfo(false), new Callback.Adapter());

                Session session = stream.getSession();
                // Since it's unidirectional, no need to pass the listener
                session.syn(new SynInfo(new Fields(), false, (byte)0), null, new Promise.Adapter<Stream>()
                {
                    @Override
                    public void succeeded(Stream pushStream)
                    {
                        pushStream.data(new StringDataInfo("foo", false), new Callback.Adapter());
                    }
                });
                return null;
            }
        };
        Assert.assertNotNull(ssfl);
    }
}
