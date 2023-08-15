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

package org.eclipse.jetty.ee10.servlet.security;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.ee10.servlet.ServletChannel;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * FORM Authenticator.
 *
 * <p>This authenticator implements form authentication will use dispatchers to
 * the login page if the {@link #__FORM_DISPATCH} init parameter is set to true.
 * Otherwise it will redirect.</p>
 *
 * <p>The form authenticator redirects unauthenticated requests to a log page
 * which should use a form to gather username/password from the user and send them
 * to the /j_security_check URI within the context.  FormAuthentication uses
 * {@link SessionAuthentication} to wrap Authentication results so that they
 * are  associated with the session.</p>
 */
public class FormAuthenticator extends org.eclipse.jetty.security.authentication.FormAuthenticator
{
    public static final String __FORM_DISPATCH = "org.eclipse.jetty.security.dispatch";

    private boolean _dispatch;

    public FormAuthenticator()
    {
    }

    public FormAuthenticator(String login, String error, boolean dispatch)
    {
        super(login, error, dispatch);
        _dispatch = dispatch;
    }

    @Override
    public void setConfiguration(Configuration configuration)
    {
        super.setConfiguration(configuration);

        String dispatch = configuration.getParameter(FormAuthenticator.__FORM_DISPATCH);
        _dispatch = dispatch == null ? _dispatch : Boolean.parseBoolean(dispatch);
    }

    @Override
    protected AuthenticationState sendError(Request request, Response response, Callback callback)
    {
        if (_dispatch && getErrorPage() != null)
            return dispatch(getErrorPage(), request, response, callback);
        else
            return super.sendError(request, response, callback);
    }

    @Override
    protected AuthenticationState sendChallenge(Request request, Response response, Callback callback)
    {
        if (_dispatch)
            return dispatch(getLoginPage(), request, response, callback);
        else
            return super.sendChallenge(request, response, callback);
    }

    private AuthenticationState dispatch(String path, Request request, Response response, Callback callback)
    {
        try
        {
            response.getHeaders().put(HttpHeader.CACHE_CONTROL.asString(), HttpHeaderValue.NO_CACHE.asString());
            response.getHeaders().putDate(HttpHeader.EXPIRES.asString(), 1);

            ServletContextRequest contextRequest = Request.as(request, ServletContextRequest.class);
            contextRequest.setAttribute(ServletChannel.INITIAL_DISPATCH_PATH, path);
            contextRequest.setAttribute(ServletChannel.INITIAL_DISPATCH_REQUEST, new FormRequest(contextRequest.getServletApiRequest()));
            contextRequest.setAttribute(ServletChannel.INITIAL_DISPATCH_RESPONSE, new FormResponse(contextRequest.getHttpServletResponse()));
            contextRequest.getServletChannel().initialDispatch();

            return AuthenticationState.DEFER;
        }
        catch (Throwable t)
        {
            Response.writeError(request, response, callback, t);
            return AuthenticationState.SEND_FAILURE;
        }
    }

    protected static class FormRequest extends HttpServletRequestWrapper
    {
        public FormRequest(HttpServletRequest request)
        {
            super(request);
        }

        @Override
        public long getDateHeader(String name)
        {
            if (name.toLowerCase(Locale.ENGLISH).startsWith("if-"))
                return -1;
            return super.getDateHeader(name);
        }

        @Override
        public String getHeader(String name)
        {
            if (name.toLowerCase(Locale.ENGLISH).startsWith("if-"))
                return null;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames()
        {
            return Collections.enumeration(Collections.list(super.getHeaderNames()));
        }

        @Override
        public Enumeration<String> getHeaders(String name)
        {
            if (name.toLowerCase(Locale.ENGLISH).startsWith("if-"))
                return Collections.<String>enumeration(Collections.<String>emptyList());
            return super.getHeaders(name);
        }
    }

    protected static class FormResponse extends HttpServletResponseWrapper
    {
        public FormResponse(HttpServletResponse response)
        {
            super(response);
        }

        @Override
        public void addDateHeader(String name, long date)
        {
            if (notIgnored(name))
                super.addDateHeader(name, date);
        }

        @Override
        public void addHeader(String name, String value)
        {
            if (notIgnored(name))
                super.addHeader(name, value);
        }

        @Override
        public void setDateHeader(String name, long date)
        {
            if (notIgnored(name))
                super.setDateHeader(name, date);
        }

        @Override
        public void setHeader(String name, String value)
        {
            if (notIgnored(name))
                super.setHeader(name, value);
        }

        private boolean notIgnored(String name)
        {
            if (HttpHeader.CACHE_CONTROL.is(name) ||
                HttpHeader.PRAGMA.is(name) ||
                HttpHeader.ETAG.is(name) ||
                HttpHeader.EXPIRES.is(name) ||
                HttpHeader.LAST_MODIFIED.is(name) ||
                HttpHeader.AGE.is(name))
                return false;
            return true;
        }
    }
}
