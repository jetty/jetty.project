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

import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.rhttp.client.RHTTPResponse;


/**
 * <p><tt>ExternalRequest</tt> represent an external request made to the gateway server.</p>
 * <p><tt>ExternalRequest</tt>s that arrive to the gateway server are suspended, waiting
 * for a response from the corresponding gateway client.</p>
 *
 * @version $Revision$ $Date$
 */
public interface ExternalRequest
{
    /**
     * <p>Suspends this <tt>ExternalRequest</tt> waiting for a response from the gateway client.</p>
     * @return true if the <tt>ExternalRequest</tt> has been suspended, false if the
     * <tt>ExternalRequest</tt> has already been responded.
     */
    public boolean suspend();

    /**
     * <p>Responds to the original external request with the response arrived from the gateway client.</p>
     * @param response the response arrived from the gateway client
     * @throws IOException if responding to the original external request fails
     */
    public void respond(RHTTPResponse response) throws IOException;

    /**
     * @return the request to be sent to the gateway client
     */
    public RHTTPRequest getRequest();
}
