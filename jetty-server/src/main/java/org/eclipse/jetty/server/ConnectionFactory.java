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
import java.util.List;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

/**
 * A Factory to create {@link Connection} instances for {@link Connector}s.
 * <p>
 * A Connection factory is responsible for instantiating and configuring a {@link Connection} instance
 * to handle an {@link EndPoint} accepted by a {@link Connector}.
 * <p>
 * A ConnectionFactory has a protocol name that represents the protocol of the Connections
 * created.  Example of protocol names include:
 * <dl>
 * <dt>http</dt><dd>Creates an HTTP connection that can handle multiple versions of HTTP from 0.9 to 1.1</dd>
 * <dt>h2</dt><dd>Creates an HTTP/2 connection that handles the HTTP/2 protocol</dd>
 * <dt>SSL-XYZ</dt><dd>Create an SSL connection chained to a connection obtained from a connection factory
 * with a protocol "XYZ".</dd>
 * <dt>SSL-http</dt><dd>Create an SSL connection chained to an HTTP connection (aka https)</dd>
 * <dt>SSL-ALPN</dt><dd>Create an SSL connection chained to a ALPN connection, that uses a negotiation with
 * the client to determine the next protocol.</dd>
 * </dl>
 */
public interface ConnectionFactory
{

    /**
     * @return A string representing the primary protocol name.
     */
    String getProtocol();

    /**
     * @return A list of alternative protocol names/versions including the primary protocol.
     */
    List<String> getProtocols();

    /**
     * <p>Creates a new {@link Connection} with the given parameters</p>
     *
     * @param connector The {@link Connector} creating this connection
     * @param endPoint the {@link EndPoint} associated with the connection
     * @return a new {@link Connection}
     */
    Connection newConnection(Connector connector, EndPoint endPoint);

    interface Upgrading extends ConnectionFactory
    {

        /**
         * Create a connection for an upgrade request.
         * <p>This is a variation of {@link #newConnection(Connector, EndPoint)} that can create (and/or customise)
         * a connection for an upgrade request.  Implementations may call {@link #newConnection(Connector, EndPoint)} or
         * may construct the connection instance themselves.</p>
         *
         * @param connector The connector to upgrade for.
         * @param endPoint The endpoint of the connection.
         * @param upgradeRequest The meta data of the upgrade request.
         * @param responseFields The fields to be sent with the 101 response
         * @return Null to indicate that request processing should continue normally without upgrading. A new connection instance to
         * indicate that the upgrade should proceed.
         * @throws BadMessageException Thrown to indicate the upgrade attempt was illegal and that a bad message response should be sent.
         */
        Connection upgradeConnection(Connector connector, EndPoint endPoint, MetaData.Request upgradeRequest, HttpFields responseFields) throws BadMessageException;
    }

    /**
     * <p>Connections created by this factory MUST implement {@link Connection.UpgradeTo}.</p>
     */
    interface Detecting extends ConnectionFactory
    {
        /**
         * The possible outcomes of the {@link #detect(ByteBuffer)} method.
         */
        enum Detection
        {
            /**
             * A {@link Detecting} can work with the given bytes.
             */
            RECOGNIZED,
            /**
             * A {@link Detecting} cannot work with the given bytes.
             */
            NOT_RECOGNIZED,
            /**
             * A {@link Detecting} requires more bytes to make a decision.
             */
            NEED_MORE_BYTES
        }

        /**
         * <p>Check the bytes in the given {@code buffer} to figure out if this {@link Detecting} instance
         * can work with them or not.</p>
         * <p>The {@code buffer} MUST be left untouched by this method: bytes MUST NOT be consumed and MUST NOT be modified.</p>
         * @param buffer the buffer.
         * @return One of:
         * <ul>
         * <li>{@link Detection#RECOGNIZED} if this {@link Detecting} instance can work with the bytes in the buffer</li>
         * <li>{@link Detection#NOT_RECOGNIZED} if this {@link Detecting} instance cannot work with the bytes in the buffer</li>
         * <li>{@link Detection#NEED_MORE_BYTES} if this {@link Detecting} instance requires more bytes to make a decision</li>
         * </ul>
         */
        Detection detect(ByteBuffer buffer);
    }
}
