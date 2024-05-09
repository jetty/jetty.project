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

package org.eclipse.jetty.io.ssl;

import java.util.EventListener;
import java.util.EventObject;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import org.eclipse.jetty.io.EndPoint;

/**
 * <p>Implementations of this interface are notified of TLS handshake events.</p>
 * <p>Similar to {@link javax.net.ssl.HandshakeCompletedListener}, but for {@link SSLEngine}.</p>
 * <p>Typical usage if to add instances of this class as beans to a server connector, or
 * to a client connector.</p>
 */
public interface SslHandshakeListener extends EventListener
{
    /**
     * <p>Callback method invoked when the TLS handshake succeeds.</p>
     *
     * @param event the event object carrying information about the TLS handshake event
     * @throws SSLException if any error happen during handshake
     */
    default void handshakeSucceeded(Event event) throws SSLException
    {
    }

    /**
     * <p>Callback method invoked when the TLS handshake fails.</p>
     *
     * @param event the event object carrying information about the TLS handshake event
     * @param failure the failure that caused the TLS handshake to fail
     */
    default void handshakeFailed(Event event, Throwable failure)
    {
    }

    /**
     * <p>The event object carrying information about TLS handshake events.</p>
     */
    class Event extends EventObject
    {
        private final EndPoint endPoint;

        /**
         * <p>Creates a new instance with the given event source.</p>
         *
         * @param source the source of this event.
         * @deprecated instances of this class can only be created by the implementation
         */
        @Deprecated(forRemoval = true, since = "12.0.7")
        public Event(Object source)
        {
            this(source, null);
        }

        Event(Object sslEngine, EndPoint endPoint)
        {
            super(sslEngine);
            this.endPoint = endPoint;
        }

        /**
         * @return the SSLEngine associated to the TLS handshake event
         */
        public SSLEngine getSSLEngine()
        {
            return (SSLEngine)getSource();
        }

        /**
         * @return the EndPoint associated to the TLS handshake event
         */
        public EndPoint getEndPoint()
        {
            return endPoint;
        }
    }
}
