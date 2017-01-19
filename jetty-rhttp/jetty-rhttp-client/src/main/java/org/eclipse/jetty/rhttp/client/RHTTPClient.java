//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.client;

import java.io.IOException;

/**
 * <p><tt>RHTTPClient</tt> represent a client of the gateway server.</p>
 * <p>A <tt>Client</tt> has a server side counterpart with which communicates
 * using a comet protocol.<br /> The <tt>Client</tt>, its server-side
 * counterpart and the comet protocol form the <em>Half-Object plus Protocol</em>
 * pattern.</p>
 * <p>A <tt>Client</tt> must first connect to the gateway server, to let the gateway
 * server know its targetId, an identifier that uniquely distinguish this
 * <tt>Client</tt> from other <tt>Client</tt>s.</p>
 * <p>Once connected, the gateway server will use a comet procotol to notify the
 * <tt>Client</tt> of server-side events, and the <tt>Client</tt> can send
 * information to the gateway server to notify it of client-side events.</p>
 * <p>Server-side event are notified to {@link RHTTPListener}s, while relevant
 * network events are communicated to {@link ClientListener}s.</p>
 *
 * @version $Revision$ $Date$
 */
public interface RHTTPClient
{
    /**
     * @return The gateway uri, typically "http://gatewayhost:gatewayport/gatewaypath".
     */
    public String getGatewayURI();
    
    /**
     * @return The gateway host
     */
    public String getHost();
    
    /**
     * @return The gateway port
     */
    public int getPort();
    
    /**
     * @return The gateway path
     */
    public String getPath();
    
    /**
     * @return the targetId that uniquely identifies this client.
     */
    public String getTargetId();

    /**
     * <p>Connects to the gateway server, establishing the long poll communication
     * with the gateway server to be notified of server-side events.</p>
     * <p>The connect is performed in two steps:
     * <ul>
     * <li>first, a connect message is sent to the gateway server; the gateway server
     * will notice this is a first connect message and reply immediately with
     * an empty response</li>
     * <li>second, another connect message is sent to the gateway server which interprets
     * it as a long poll request</li>
     * </ul>
     * The long poll request may return either because one or more server-side events
     * happened, or because it expired. </p>
     * <p>Any connect message after the first is treated as a long poll request.</p>
     *
     * @throws IOException if it is not possible to connect to the gateway server
     * @see #disconnect()
     */
    public void connect() throws IOException;

    /**
     * <p>Disconnects from the gateway server.</p>
     * <p>Just after the disconnect request is processed by to the gateway server, it will
     * return the currently outstanding long poll request.</p>
     * <p>If this client is not connected, it does nothing</p>
     *
     * @throws IOException if it is not possible to contact the gateway server to disconnect
     * @see #connect()
     */
    public void disconnect() throws IOException;

    /**
     * <p>Sends a response to the gateway server.</p>
     *
     * @param response the response to send
     * @throws IOException if it is not possible to contact the gateway server
     */
    public void deliver(RHTTPResponse response) throws IOException;

    /**
     * <p>Adds the given listener to this client.</p>
     * @param listener the listener to add
     * @see #removeListener(RHTTPListener)
     */
    public void addListener(RHTTPListener listener);

    /**
     * <p>Removes the given listener from this client.</p>
     * @param listener the listener to remove
     * @see #addListener(RHTTPListener)
     */
    public void removeListener(RHTTPListener listener);

    /**
     * <p>Adds the given client listener to this client.</p>
     * @param listener the client listener to add
     * @see #removeClientListener(ClientListener)
     */
    public void addClientListener(ClientListener listener);

    /**
     * <p>Removes the given client listener from this client.</p>
     * @param listener the client listener to remove
     * @see #addClientListener(ClientListener)
     */
    public void removeClientListener(ClientListener listener);
}
