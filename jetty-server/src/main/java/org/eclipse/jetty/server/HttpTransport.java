//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.Callback;

/**
 * Abstraction of the outbound HTTP transport.
 */
public interface HttpTransport
{
    /**
     * Asynchronous call to send a response (or part) over the transport
     *
     * @param info The header info to send, or null if just sending more data.
     * The first call to send for a response must have a non null info.
     * @param head True if the response if for a HEAD request (and the data should not be sent).
     * @param content A buffer of content to be sent.
     * @param lastContent True if the content is the last content for the current response.
     * @param callback The Callback instance that success or failure of the send is notified on
     */
    void send(MetaData.Response info, boolean head, ByteBuffer content, boolean lastContent, Callback callback);

    /**
     * @return true if responses can be pushed over this transport
     */
    boolean isPushSupported();

    /**
     * @param request A request to use as the basis for generating a pushed response.
     */
    void push(MetaData.Request request);

    /**
     * Called to indicated the end of the current request/response cycle (which may be
     * some time after the last content is sent).
     */
    void onCompleted();

    /**
     * Aborts this transport.
     * <p>
     * This method should terminate the transport in a way that
     * can indicate an abnormal response to the client, for example
     * by abruptly close the connection.
     * <p>
     * This method is called when an error response needs to be sent,
     * but the response is already committed, or when a write failure
     * is detected.  If abort is called, {@link #onCompleted()} is not
     * called
     *
     * @param failure the failure that caused the abort.
     */
    void abort(Throwable failure);

    /**
     * Is the underlying transport optimized for DirectBuffer usage
     *
     * @return True if direct buffers can be used optimally.
     */
    boolean isOptimizedForDirectBuffers();
}
