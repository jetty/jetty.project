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

package org.eclipse.jetty.spdy.api;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.StandardSession;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class ClientUsageTest
{
    @Test
    public void testClientRequestResponseNoBody() throws Exception
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, null, null, null);

        session.syn(new SynInfo(true), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // Do something with the response
                replyInfo.getHeaders().get("host");

                // Then issue another similar request
                stream.getSession().syn(new SynInfo(true), this);
            }
        });
    }

    @Test
    public void testClientRequestWithBodyResponseNoBody() throws Exception
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, null, null, null);

        Stream stream = session.syn(new SynInfo(false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // Do something with the response
                replyInfo.getHeaders().get("host");

                // Then issue another similar request
                stream.getSession().syn(new SynInfo(true), this);
            }
        }).get(5, TimeUnit.SECONDS);
        // Send-and-forget the data
        stream.data(new StringDataInfo("data", true));
    }

    @Test
    public void testAsyncClientRequestWithBodyResponseNoBody() throws Exception
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, null, null, null);

        final String context = "context";
        session.syn(new SynInfo(false), new StreamFrameListener.Adapter()
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // Do something with the response
                replyInfo.getHeaders().get("host");

                // Then issue another similar request
                stream.getSession().syn(new SynInfo(true), this);
            }
        }, 0, TimeUnit.MILLISECONDS, new Handler.Adapter<Stream>()
        {
            @Override
            public void completed(Stream stream)
            {
                // Differently from JDK 7 AIO, there is no need to
                // have an explicit parameter for the context since
                // that is captured while the handler is created anyway,
                // and it is used only by the handler as parameter

                // The style below is fire-and-forget, since
                // we do not pass the handler nor we call get()
                // to wait for the data to be sent
                stream.data(new StringDataInfo(context, true));
            }
        });
    }

    @Test
    public void testAsyncClientRequestWithBodyAndResponseWithBody() throws Exception
    {
        Session session = new StandardSession(SPDY.V2, null, null, null, null, null, 1, null, null, null);

        session.syn(new SynInfo(false), new StreamFrameListener.Adapter()
        {
            // The good of passing the listener to syn() is that applications can safely
            // accumulate info from the reply headers to be used in the data callback,
            // e.g. content-type, charset, etc.

            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
                // Do something with the response
                Headers headers = replyInfo.getHeaders();
                int contentLength = headers.get("content-length").valueAsInt();
                stream.setAttribute("content-length", contentLength);
                if (!replyInfo.isClose())
                    stream.setAttribute("builder", new StringBuilder());

                // May issue another similar request while waiting for data
                stream.getSession().syn(new SynInfo(true), this);
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                StringBuilder builder = (StringBuilder)stream.getAttribute("builder");
                builder.append(dataInfo.asString("UTF-8", true));
                if (dataInfo.isClose())
                {
                    int receivedLength = builder.toString().getBytes(Charset.forName("UTF-8")).length;
                    assert receivedLength == (Integer)stream.getAttribute("content-length");
                }

            }
        }, 0, TimeUnit.MILLISECONDS, new Handler.Adapter<Stream>()
        {
            @Override
            public void completed(Stream stream)
            {
                stream.data(new BytesDataInfo("wee".getBytes(Charset.forName("UTF-8")), false));
                stream.data(new StringDataInfo("foo", false));
                stream.data(new ByteBufferDataInfo(Charset.forName("UTF-8").encode("bar"), true));
            }
        });
    }
}
