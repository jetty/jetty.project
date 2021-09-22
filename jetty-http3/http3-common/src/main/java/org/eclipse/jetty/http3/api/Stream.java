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

    public void demand();

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
        private final Runnable complete;

        public Data(DataFrame frame, Runnable complete)
        {
            this.frame = frame;
            this.complete = complete;
        }

        public DataFrame frame()
        {
            return frame;
        }

        public void complete()
        {
            complete.run();
        }

        @Override
        public String toString()
        {
            return String.format("%s[%s]", getClass().getSimpleName(), frame);
        }
    }
}
