//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;

/**
 * <p>A protocol handler that handles redirect status codes 301, 302, 303, 307 and 308.</p>
 */
public class RedirectProtocolHandler implements ProtocolHandler, Response.Listener
{
    public static final String NAME = "redirect";

    private final HttpRedirector redirector;

    public RedirectProtocolHandler(HttpClient client)
    {
        redirector = new HttpRedirector(client);
    }

    @Override
    public String getName()
    {
        return NAME;
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
    public boolean onHeader(Response response, HttpField field)
    {
        // Avoid that the content is decoded, which could generate
        // errors, since we are discarding the response content anyway.
        return field.getHeader() != HttpHeader.CONTENT_ENCODING;
    }

    @Override
    public void onSuccess(Response response)
    {
        // The request may still be sending content, stop it.
        Request request = response.getRequest();
        if (request.getBody() != null)
            request.abort(new HttpRequestException("Aborting request after receiving a %d response".formatted(response.getStatus()), request));
    }

    @Override
    public void onComplete(Result result)
    {
        Request request = result.getRequest();
        Response response = result.getResponse();
        if (result.getResponseFailure() == null)
            redirector.redirect(request, response, null);
        else
            redirector.fail(request, response, result.getFailure());
    }
}
