//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.gateway;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.rhttp.client.RHTTPRequest;


/**
 * <p>A <tt>ClientDelegate</tt> is the server-side counterpart of a gateway client.</p>
 * <p>The gateway client, the comet protocol and the <tt>ClientDelegate</tt> form the
 * <em>Half-Object plus Protocol</em> pattern that is used between the gateway server
 * and the gateway client.</p>
 * <p><tt>ClientDelegate</tt> offers a server-side API on top of the comet communication.<br />
 * The API allows to enqueue server-side events to the gateway client, allows to
 * flush them to the gateway client, and allows to close and dispose server-side
 * resources when the gateway client disconnects.</p>
 *
 * @version $Revision$ $Date$
 */
public interface ClientDelegate
{
    /**
     * @return the targetId that uniquely identifies this client delegate.
     */
    public String getTargetId();

    /**
     * <p>Enqueues the given request to the delivery queue so that it will be sent to the
     * gateway client on the first flush occasion.</p>
     * <p>Requests may fail to be queued, for example because the gateway client disconnected
     * concurrently.</p>
     *
     * @param request the request to add to the delivery queue
     * @return whether the request has been queued or not
     * @see #process(HttpServletRequest)
     */
    public boolean enqueue(RHTTPRequest request);

    /**
     * <p>Flushes the requests that have been {@link #enqueue(RHTTPRequest) enqueued}.</p>
     * <p>If no requests have been enqueued, then this method may suspend the current request for
     * the long poll timeout. <br />
     * The request is suspended only if all these conditions holds true:
     * <ul>
     * <li>it is not the first time that this method is called for this client delegate</li>
     * <li>no requests have been enqueued</li>
     * <li>this client delegate is not closed</li>
     * <li>the previous call to this method did not suspend the request</li>
     * </ul>
     * In all other cases, a response if sent to the gateway client, possibly containing no requests.
     *
     * @param httpRequest the HTTP request for the long poll request from the gateway client
     * @return the list of requests to send to the gateway client, or null if no response should be sent
     * to the gateway client
     * @throws IOException in case of I/O exception while flushing content to the gateway client
     * @see #enqueue(RHTTPRequest)
     */
    public List<RHTTPRequest> process(HttpServletRequest httpRequest) throws IOException;

    /**
     * <p>Closes this client delegate, in response to a gateway client request to disconnect.</p>
     * @see #isClosed()
     */
    public void close();

    /**
     * @return whether this delegate client is closed
     * @see #close()
     */
    public boolean isClosed();
}
