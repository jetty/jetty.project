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

package org.eclipse.jetty.client;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

/**
 * <p>A protocol handler performs HTTP protocol operations on
 * behalf of the application, typically like a browser would.</p>
 * <p>A typical example is handling HTTP redirects. {@link HttpClient}
 * could just return the redirect response to the application,
 * but the application would have to implement the redirect
 * functionality (while browsers do this automatically).</p>
 */
public interface ProtocolHandler
{
    /**
     * @return a unique name among protocol handlers
     */
    String getName();

    /**
     * <p>Inspects the given {@code request} and {@code response}
     * to detect whether this protocol handler should handle them.</p>
     * <p>For example, a redirect protocol handler can inspect the
     * response code and return true if it is a redirect response code.</p>
     * <p>This method is being called just after the response line has
     * been parsed, and before the response headers are available.</p>
     *
     * @param request the request to accept
     * @param response the response to accept
     * @return true if this protocol handler can handle the given request and response
     */
    boolean accept(Request request, Response response);

    /**
     * @return a response listener that will handle the request and response
     * on behalf of the application.
     */
    Response.Listener getResponseListener();
}
