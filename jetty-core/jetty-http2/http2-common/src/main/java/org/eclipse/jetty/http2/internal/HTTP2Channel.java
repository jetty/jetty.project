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

package org.eclipse.jetty.http2.internal;

import java.util.function.Consumer;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;

/**
 * <p>A HTTP/2 specific handler of events for normal and tunneled exchanges.</p>
 */
public interface HTTP2Channel
{
    /**
     * <p>A client specific handler for events that happen after
     * a {@code HEADERS} response frame is received.</p>
     * <p>{@code DATA} frames may be handled as response content
     * or as opaque tunnelled data.</p>
     */
    public interface Client
    {
        public void onData(DataFrame frame, Callback callback);

        public boolean onTimeout(Throwable failure);

        public void onFailure(Throwable failure, Callback callback);
    }

    /**
     * <p>A server specific handler for events that happen after
     * a {@code HEADERS} request frame is received.</p>
     * <p>{@code DATA} frames may be handled as request content
     * or as opaque tunnelled data.</p>
     */
    public interface Server
    {
        public Runnable onData(DataFrame frame, Callback callback);

        public Runnable onTrailer(HeadersFrame frame);

        // TODO: review the signature because the serialization done by HttpChannel.onError()
        //  is now failing the callback which fails the HttpStream, which should decide whether
        //  to reset the HTTP/2 stream, so we may not need the boolean return type.
        public boolean onTimeout(Throwable failure, Consumer<Runnable> consumer);

        // TODO: can it be simplified? The callback seems to only be succeeded, which
        //  means it can be converted into a Runnable which may just be the return type
        //  so we can get rid of the Callback parameter.
        public Runnable onFailure(Throwable failure, Callback callback);

        // TODO: is this needed?
        public boolean isIdle();
    }
}
