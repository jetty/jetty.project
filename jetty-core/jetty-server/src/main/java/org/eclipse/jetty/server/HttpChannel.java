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

package org.eclipse.jetty.server;

import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>Represents the state of an HTTP request/response cycle.</p>
 * <p>{@code HttpChannel} links the lower (closer to the network) layer {@link HttpStream}
 * with the upper (application code) layer {@link Handler}.</p>
 * <p>An {@code HttpChannel} instance may be used for many HTTP request/response cycles
 * from the same connection; however, only a single cycle may be active at any time.</p>
 * <p>Default implementations of this interface may be created via {@link DefaultFactory}.</p>
 */
public interface HttpChannel extends Invocable
{
    /**
     * @return the {@code ConnectionMetaData} associated with this channel.
     */
    ConnectionMetaData getConnectionMetaData();

    /**
     * Set the {@link HttpStream} to associate to this channel..
     * @param httpStream the {@link HttpStream} to associate to this channel.
     */
    void setHttpStream(HttpStream httpStream);

    /**
     * @return whether the request has been passed to the root {@link Handler}.
     */
    boolean isRequestHandled();

    /**
     * <p>{@link HttpStream} invokes this method when the metadata of an HTTP
     * request (method, URI and headers, but not content) has been parsed.</p>
     * <p>The returned {@code Runnable} invokes the root {@link Handler}.</p>
     *
     * @param metaData the HTTP request metadata.
     * @return a {@code Runnable} that invokes the root {@link Handler}.
     */
    Runnable onRequest(MetaData.Request metaData);

    /**
     * <p>Returns the {@link Request} object, if available.</p>
     * <p>The {@code Request} object is only available after a call to
     * {@link #onRequest(MetaData.Request)} has been made.</p>
     *
     * @return the {@code Request} object, or null if the {@code Request} object
     * is not yet available.
     */
    Request getRequest();

    /**
     * <p>{@link HttpStream} invokes this method when more HTTP request content is available.</p>
     *
     * @return the last {@code Runnable} passed to {@link Request#demand(Runnable)},
     * or {@code null} if there is no demand for content.
     */
    Runnable onContentAvailable();

    /**
     * <p>Notifies this {@code HttpChannel} that an idle timeout happened.</p>
     *
     * @param idleTimeout the timeout.
     * @return a {@code Runnable} that performs the timeout action, or {@code null}
     * if no action need be performed by the calling thread
     * @see Request#addIdleTimeoutListener(Predicate)
     */
    Runnable onIdleTimeout(TimeoutException idleTimeout);

    /**
     * <p>Notifies this {@code HttpChannel} that an asynchronous failure happened.</p>
     * <p>Typical failure examples could be HTTP/2 resets or
     * protocol failures (for example, invalid request bytes).</p>
     *
     * @param failure the failure cause.
     * @return a {@code Runnable} that performs the failure action, or {@code null}
     * if no failure action need be performed by the calling thread
     * @see Request#addFailureListener(Consumer)
     */
    Runnable onFailure(Throwable failure);

    /**
     * Recycle the HttpChannel, so that a new cycle of calling {@link #setHttpStream(HttpStream)},
     * {@link #onRequest(MetaData.Request)} etc. may be performed on the channel.
     * @see #init()
     */
    void recycle();

    /**
     * Initialize the HttpChannel when a new cycle of request handling begins.
     * @see #recycle()
     */
    void init();

    /**
     * @return the active {@link ComplianceViolation.Listener}
     */
    ComplianceViolation.Listener getComplianceViolationListener();

    /**
     * @param request attempt to resolve the HttpChannel from the provided request
     * @return the HttpChannel if found
     * @throws IllegalStateException if unable to find HttpChannel
     */
    static HttpChannel from(Request request)
    {
        while (true)
        {
            if (request instanceof Request.Wrapper wrapper)
                request = wrapper.getWrapped();
            else
                break;
        }

        if (request.getComponents() instanceof HttpChannel httpChannel)
            return httpChannel;

        throw new IllegalStateException("Unable to find HttpChannel from " + request);
    }

    /**
     * <p>A factory for {@link HttpChannel} instances.</p>
     *
     * @see DefaultFactory
     */
    interface Factory
    {
        /**
         * @param connectionMetaData the {@code ConnectionMetaData} associated with the channel.
         * @return a new {@link HttpChannel} instance.
         */
        HttpChannel newHttpChannel(ConnectionMetaData connectionMetaData);
    }

    /**
     * <p>The factory that creates default implementations of {@link HttpChannel}.</p>
     */
    class DefaultFactory implements Factory
    {
        @Override
        public HttpChannel newHttpChannel(ConnectionMetaData connectionMetaData)
        {
            return new HttpChannelState(connectionMetaData);
        }
    }
}
