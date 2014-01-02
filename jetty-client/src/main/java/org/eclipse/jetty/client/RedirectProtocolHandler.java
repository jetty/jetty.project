//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.client.api.Result;

public class RedirectProtocolHandler extends Response.Listener.Adapter implements ProtocolHandler
{
    private final HttpRedirector redirector;

    public RedirectProtocolHandler(HttpClient client)
    {
        redirector = new HttpRedirector(client);
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        return redirector.isRedirect(response) && request.isFollowRedirects();
    }

    @Override
    public Response.Listener getResponseListener()
    {
        return this;
    }

    @Override
    public void onComplete(Result result)
    {
        Request request = result.getRequest();
        Response response = result.getResponse();
        if (result.isSucceeded())
            redirector.redirect(request, response, null);
        else
            redirector.fail(request, response, result.getFailure());
    }
}
