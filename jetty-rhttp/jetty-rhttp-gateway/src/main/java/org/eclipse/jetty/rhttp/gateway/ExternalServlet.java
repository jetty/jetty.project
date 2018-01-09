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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * The servlet that handles external requests.
 *
 * @version $Revision$ $Date$
 */
public class ExternalServlet extends HttpServlet
{
    private final Logger logger = Log.getLogger(getClass().toString());
    private final Gateway gateway;
    private TargetIdRetriever targetIdRetriever;

    public ExternalServlet(Gateway gateway, TargetIdRetriever targetIdRetriever)
    {
        this.gateway = gateway;
        this.targetIdRetriever = targetIdRetriever;
    }

    public TargetIdRetriever getTargetIdRetriever()
    {
        return targetIdRetriever;
    }

    public void setTargetIdRetriever(TargetIdRetriever targetIdRetriever)
    {
        this.targetIdRetriever = targetIdRetriever;
    }

    @Override
    protected void service(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException, IOException
    {
        logger.debug("External http request: {}", httpRequest.getRequestURL());

        String targetId = targetIdRetriever.retrieveTargetId(httpRequest);
        if (targetId == null)
            throw new ServletException("Invalid request to " + getClass().getSimpleName() + ": " + httpRequest.getRequestURI());

        ClientDelegate client = gateway.getClientDelegate(targetId);
        if (client == null) throw new ServletException("Client with targetId " + targetId + " is not connected");

        ExternalRequest externalRequest = gateway.newExternalRequest(httpRequest, httpResponse);
        RHTTPRequest request = externalRequest.getRequest();
        ExternalRequest existing = gateway.addExternalRequest(request.getId(), externalRequest);
        assert existing == null;
        logger.debug("External request {} for device {}", request, targetId);

        boolean delivered = client.enqueue(request);
        if (delivered)
        {
            externalRequest.suspend();
        }
        else
        {
            // TODO: improve this: we can temporarly queue this request elsewhere and wait for the client to reconnect ?
            throw new ServletException("Could not enqueue request to client with targetId " + targetId);
        }
    }
}
