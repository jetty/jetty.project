//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.net.URI;
import java.util.List;

public interface Session
{
    /**
     * Close the current conversation with a normal status code and no reason phrase.
     */
    void close() throws IOException;

    /**
     * Close the current conversation, giving a reason for the closure. Note the websocket spec defines the acceptable uses of status codes and reason phrases.
     * 
     * @param closeStatus
     *            the reason for the closure
     */
    void close(CloseStatus closeStatus) throws IOException;

    /**
     * The maximum total length of messages, text or binary, that this Session can handle.
     * 
     * @return the message size
     */
    long getMaximumMessageSize();

    /**
     * Return the list of extensions currently in use for this conversation.
     * <p>
     * Convenience method for <code>.getUpgradeResponse().getExtensions()</code>
     * 
     * @return the negotiated extensions
     */
    List<String> getNegotiatedExtensions();

    /**
     * Return the sub protocol agreed during the websocket handshake for this conversation.
     * <p>
     * Convenience method for <code>.getUpgradeResponse().getAcceptedSubProtocol()</code>
     * 
     * @return the negotiated subprotocol
     */
    String getNegotiatedSubprotocol();

    /**
     * Returns the version of the websocket protocol currently being used. This is taken as the value of the Sec-WebSocket-Version header used in the opening
     * handshake. i.e. "13".
     * 
     * @return the protocol version
     */
    String getProtocolVersion();

    /**
     * Return the query string associated with the request this session was opened under.
     * <p>
     * Convenience method for <code>.getUpgradeRequest().getRequestURI().getQuery()</code>
     */
    String getQueryString();

    /**
     * Return a reference to the RemoteEndpoint object representing the other end of this conversation.
     * 
     * @return the remote endpoint
     */
    RemoteEndpoint getRemote();

    /**
     * Return the URI that this session was opened under.
     * <p>
     * Note, this is different than the servlet-api getRequestURI, as this will return the query portion as well.
     * <p>
     * Convenience method for <code>.getUpgradeRequest().getRequestURI()</code>
     * 
     * @return the request URI.
     */
    URI getRequestURI();

    /**
     * Return the number of milliseconds before this conversation will be closed by the container if it is inactive, ie no messages are either sent or received
     * in that time.
     * 
     * @return the timeout in milliseconds.
     */
    long getTimeout();

    /**
     * Get the UpgradeRequest used to create this session
     * 
     * @return the UpgradeRequest used to create this session
     */
    UpgradeRequest getUpgradeRequest();

    /**
     * Get the UpgradeResponse used to create this session
     * 
     * @return the UpgradeResponse used to create this session
     */
    UpgradeResponse getUpgradeResponse();

    /**
     * Return true if and only if the underlying socket is open.
     * 
     * @return whether the session is active
     */
    abstract boolean isActive();

    /**
     * Return true if and only if the underlying socket is using a secure transport.
     * 
     * @return whether its using a secure transport
     */
    boolean isSecure();

    /**
     * Sets the maximum total length of messages, text or binary, that this Session can handle.
     */
    void setMaximumMessageSize(long length);

    /**
     * Set the number of milliseconds before this conversation will be closed by the container if it is inactive, ie no messages are either sent or received.
     * 
     * @param ms
     *            the number of milliseconds.
     */
    void setTimeout(long ms);
}
