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

package org.eclipse.jetty.client;

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
    public String getName();

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
    public boolean accept(Request request, Response response);

    /**
     * @return a response listener that will handle the request and response
     * on behalf of the application.
     */
    public Response.Listener getResponseListener();
}
