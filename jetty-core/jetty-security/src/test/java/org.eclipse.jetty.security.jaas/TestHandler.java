
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

package org.eclipse.jetty.security.jaas;

import java.util.List;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

public class TestHandler extends Handler.Abstract
{
    private List<String> _hasRoles;
    private List<String> _hasntRoles;
    
    public TestHandler(List<String> hasRoles, List<String> hasntRoles)
    {
        _hasRoles = hasRoles;
        _hasntRoles = hasntRoles;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (_hasRoles == null && _hasntRoles == null)
        {
            try
            {
                // TODO this is wrong
                AuthenticationState.authenticate(request, response, callback);
            }
            catch (Throwable e)
            {
                //TODO: an Exception should only be thrown here if the response has
                //not been set, but currently it seems that the ServletException is thrown
                //anyway by ServletContextRequest.ServletApiRequest.authenticate.
            }
        }
        else
        {
            testHasRoles(request, response);
            testHasntRoles(request, response);

            response.setStatus(200);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
            Content.Sink.write(response, true, "All OK\nrequestURI=" + request.getHttpURI(), callback);
        }
        return true;
    }

    private void testHasRoles(Request request, Response response)
    {
        if (_hasRoles != null)
        {
            AuthenticationState authenticationState = AuthenticationState.getAuthenticationState(request);
            if (authenticationState instanceof AuthenticationState.Succeeded userAuthentication)
            {
                for (String role : _hasRoles)
                {
                    if (!userAuthentication.isUserInRole(role))
                        throw new BadMessageException(HttpStatus.FORBIDDEN_403, "! in role " + role);
                }
            }
        }
    }
    
    private void testHasntRoles(Request request, Response response)
    {
        if (_hasntRoles != null)
        {
            AuthenticationState authenticationState = AuthenticationState.getAuthenticationState(request);
            if (authenticationState instanceof AuthenticationState.Succeeded userAuthentication)
            {
                for (String role : _hasntRoles)
                {
                    if (userAuthentication.isUserInRole(role))
                        throw new HttpException.RuntimeException(500, "in role " + role);
                }
            }
        }
    }
}