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

package org.eclipse.jetty.client.api;

import java.io.Closeable;

import org.eclipse.jetty.util.Promise;

/**
 * {@link Connection} represent a connection to a {@link Destination} and allow applications to send
 * requests via {@link #send(Request, Response.CompleteListener)}.
 * <p>
 * {@link Connection}s are normally pooled by {@link Destination}s, but unpooled {@link Connection}s
 * may be created by applications that want to do their own connection management via
 * {@link Destination#newConnection(Promise)} and {@link Connection#close()}.
 */
public interface Connection extends Closeable
{
    /**
     * Sends a request with an associated response listener.
     * <p>
     * {@link Request#send(Response.CompleteListener)} will eventually call this method to send the request.
     * It is exposed to allow applications to send requests via unpooled connections.
     *
     * @param request the request to send
     * @param listener the response listener
     */
    void send(Request request, Response.CompleteListener listener);

    @Override
    void close();

    /**
     * @return whether this connection has been closed
     * @see #close()
     */
    boolean isClosed();
}
