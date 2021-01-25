//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
