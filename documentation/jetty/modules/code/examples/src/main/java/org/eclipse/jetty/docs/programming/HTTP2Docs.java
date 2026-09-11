//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

@SuppressWarnings("unused")
public class HTTP2Docs
{
    public void dataDemanded() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        http2Client.start();
        SocketAddress serverAddress = new InetSocketAddress("localhost", 8080);
        CompletableFuture<Session> sessionCF = http2Client.connect(serverAddress, new Session.Listener() {});
        Session session = sessionCF.get();

        HttpFields requestHeaders = HttpFields.build()
            .put(HttpHeader.USER_AGENT, "Jetty HTTP2Client {jetty-version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, null, true);

        // tag::dataUnwrap[]
        record Data(RetainableByteBuffer buffer, Runnable demander)
        {
        }

        // A queue that consumers poll to consume content asynchronously.
        Queue<Data> dataQueue = new ConcurrentLinkedQueue<>();

        // Implementation of Stream.Listener.onDataAvailable(Stream stream)
        // in case of unwrapping of the Chunk object for asynchronous content
        // consumption and demand.
        session.newStream(headersFrame, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                try (Content.Chunk chunk = stream.read())
                {
                    if (chunk == null)
                    {
                        stream.demand();
                        return;
                    }

                    // Get the content buffer and wrap it into a record.
                    // The release of this buffer is performed by the
                    // code that consumes the Data objects from the queue.
                    RetainableByteBuffer buffer = chunk.acquire();
                    dataQueue.offer(new Data(buffer, () ->
                    {
                        if (!chunk.isLast())
                            stream.demand();
                    }));

                    // Do not demand more data here, to avoid to overflow the queue.
                }
            }
        });
        // end::dataUnwrap[]
    }
}
