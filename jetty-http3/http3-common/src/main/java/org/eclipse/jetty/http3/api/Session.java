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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http3.frames.HeadersFrame;

public interface Session
{
    public interface Client
    {
        public CompletableFuture<Stream> newStream(HeadersFrame frame, Stream.Listener listener);

        public interface Listener extends Session.Listener
        {
        }
    }

    public interface Server
    {
        public interface Listener extends Session.Listener
        {
            // TODO: accept event.
        }
    }

    public interface Listener
    {
        public default Map<Long, Long> onPreface(Session session)
        {
            return null;
        }

        public default Stream.Listener onHeaders(Stream stream, HeadersFrame frame)
        {
            return null;
        }
    }
}
