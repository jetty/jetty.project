//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Invocable;

public class HTTP3Stream implements Stream
{
    private final HTTP3Session session;
    private final QuicStreamEndPoint endPoint;
    private Listener listener;

    public HTTP3Stream(HTTP3Session session, QuicStreamEndPoint endPoint)
    {
        this.session = session;
        this.endPoint = endPoint;
    }

    public Listener getListener()
    {
        return listener;
    }

    public void setListener(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public CompletableFuture<Stream> respond(HeadersFrame frame)
    {
        return writeFrame(frame);
    }

    @Override
    public CompletableFuture<Stream> data(DataFrame frame)
    {
        return writeFrame(frame);
    }

    @Override
    public Data readData()
    {
        HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
        return connection.readData();
    }

    @Override
    public void demand(boolean enable)
    {
        HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
        connection.demand(enable);
    }

    @Override
    public CompletableFuture<Stream> trailer(HeadersFrame frame)
    {
        if (!frame.isLast())
            throw new IllegalArgumentException("invalid trailer frame: property 'last' must be true");
        return writeFrame(frame);
    }

    public boolean hasDemand()
    {
        HTTP3StreamConnection connection = (HTTP3StreamConnection)endPoint.getConnection();
        return connection.hasDemand();
    }

    private Promise.Completable<Stream> writeFrame(Frame frame)
    {
        Promise.Completable<Stream> completable = new Promise.Completable<>();
        session.writeFrame(endPoint.getStreamId(), frame, Callback.from(Invocable.InvocationType.NON_BLOCKING, () -> completable.succeeded(this), completable::failed));
        return completable;
    }
}
