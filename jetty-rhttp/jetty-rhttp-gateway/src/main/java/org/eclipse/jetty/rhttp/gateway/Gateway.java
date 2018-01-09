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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * <p>Gateway instances are responsible of holding the state of the gateway server.</p>
 * <p>The state is composed by:
 * <ul>
 * <li>{@link ExternalRequest external requests} that are suspended waiting for the response</li>
 * <li>{@link ClientDelegate gateway clients} that are connected with the gateway server</li>
 * </ul></p>
 * <p>Instances of this class are created by the {@link GatewayServer}.</p>
 *
 * @version $Revision$ $Date$
 */
public interface Gateway
{
    /**
     * <p>Returns the {@link ClientDelegate} with the given targetId.<br />
     * If there is no such ClientDelegate returns null.</p>
     *
     * @param targetId the targetId of the ClientDelegate to return
     * @return the ClientDelegate associated with the given targetId
     */
    public ClientDelegate getClientDelegate(String targetId);

    /**
     * <p>Creates and configures a new {@link ClientDelegate} with the given targetId.</p>
     * @param targetId the targetId of the ClientDelegate to create
     * @return a newly created ClientDelegate
     * @see #addClientDelegate(String, ClientDelegate)
     */
    public ClientDelegate newClientDelegate(String targetId);

    /**
     * <p>Maps the given ClientDelegate to the given targetId.</p>
     * @param targetId the targetId of the given ClientDelegate
     * @param client the ClientDelegate to map
     * @return the previously existing ClientDelegate mapped to the same targetId
     * @see #removeClientDelegate(String)
     */
    public ClientDelegate addClientDelegate(String targetId, ClientDelegate client);

    /**
     * <p>Removes the {@link ClientDelegate} associated with the given targetId.</p>
     * @param targetId the targetId of the ClientDelegate to remove
     * @return the removed ClientDelegate, or null if no ClientDelegate was removed
     * @see #addClientDelegate(String, ClientDelegate)
     */
    public ClientDelegate removeClientDelegate(String targetId);

    /**
     * <p>Creates a new {@link ExternalRequest} from the given HTTP request and HTTP response.</p>
     * @param httpRequest the HTTP request of the external request
     * @param httpResponse the HTTP response of the external request
     * @return a newly created ExternalRequest
     * @throws IOException in case of failures creating the ExternalRequest
     * @see #addExternalRequest(int, ExternalRequest)
     */
    public ExternalRequest newExternalRequest(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException;

    /**
     * Maps the given ExternalRequest with the given requestId into the gateway state.
     * @param requestId the id of the ExternalRequest
     * @param externalRequest the ExternalRequest to map
     * @return the previously existing ExternalRequest mapped to the same requestId
     * @see #removeExternalRequest(int)
     */
    public ExternalRequest addExternalRequest(int requestId, ExternalRequest externalRequest);

    /**
     * Removes the ExternalRequest mapped to the given requestId from the gateway state.
     * @param requestId the id of the ExternalRequest
     * @return the removed ExternalRequest
     * @see #addExternalRequest(int, ExternalRequest)
     */
    public ExternalRequest removeExternalRequest(int requestId);
}
