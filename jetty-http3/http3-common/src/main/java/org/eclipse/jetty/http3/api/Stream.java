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

package org.eclipse.jetty.http3.api;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;

public interface Stream
{
    public CompletableFuture<Stream> respond(HeadersFrame frame);

    public CompletableFuture<Stream> data(DataFrame dataFrame);

    public Stream.Data readData();

    public void demand(boolean enable);

    public CompletableFuture<Stream> trailer(HeadersFrame frame);

    public interface Listener
    {
        public default void onResponse(Stream stream, HeadersFrame frame)
        {
        }

        public default void onDataAvailable(Stream stream)
        {
        }

        public default void onTrailer(Stream stream, HeadersFrame frame)
        {
        }
    }

    public static class Data
    {
        private final DataFrame frame;
        private final CompletableFuture<Object> callback;

        public Data(DataFrame frame, CompletableFuture<Object> callback)
        {
            this.frame = frame;
            this.callback = callback;
        }

        public DataFrame frame()
        {
            return frame;
        }

        public CompletableFuture<Object> callback()
        {
            return callback;
        }

        public void complete(Object result, Throwable failure)
        {
            if (failure == null)
                callback().complete(result);
            else
                callback().completeExceptionally(failure);
        }

        public void completeAndDemand(Stream stream, Throwable failure)
        {
            complete(stream, failure);
            if (failure == null)
                stream.demand(true);
        }

        public void succeed()
        {
            callback().complete(null);
        }
    }
}
