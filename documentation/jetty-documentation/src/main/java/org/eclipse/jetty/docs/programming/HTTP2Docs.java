//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.ByteBuffer;
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
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;

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
            .put(HttpHeader.USER_AGENT, "Jetty HTTP2Client {version}");
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:8080/path"), HttpVersion.HTTP_2, requestHeaders);
        HeadersFrame headersFrame = new HeadersFrame(request, null, true);

        // tag::dataDemanded[]
        class Chunk
        {
            private final ByteBuffer buffer;
            private final Callback callback;

            Chunk(ByteBuffer buffer, Callback callback)
            {
                this.buffer = buffer;
                this.callback = callback;
            }
        }

        // A queue that consumers poll to consume content asynchronously.
        Queue<Chunk> dataQueue = new ConcurrentLinkedQueue<>();

        // Implementation of Stream.Listener.onDataDemanded(...)
        // in case of asynchronous content consumption and demand.
        Stream.Listener listener = new Stream.Listener()
        {
            @Override
            public void onDataDemanded(Stream stream, DataFrame frame, Callback callback)
            {
                // Get the content buffer.
                ByteBuffer buffer = frame.getData();

                // Store buffer to consume it asynchronously, and wrap the callback.
                dataQueue.offer(new Chunk(buffer, Callback.from(() ->
                {
                    // When the buffer has been consumed, then:
                    // A) succeed the nested callback.
                    callback.succeeded();
                    // B) demand more DATA frames.
                    stream.demand(1);
                }, callback::failed)));

                // Do not demand more content here, to avoid to overflow the queue.
            }
        };
        // end::dataDemanded[]
    }
}
