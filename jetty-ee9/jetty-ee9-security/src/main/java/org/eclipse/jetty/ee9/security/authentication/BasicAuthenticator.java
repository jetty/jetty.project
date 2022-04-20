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

package org.eclipse.jetty.ee9.security.authentication;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.Authentication;
import org.eclipse.jetty.ee9.nested.Authentication.User;
import org.eclipse.jetty.ee9.nested.UserIdentity;
import org.eclipse.jetty.ee9.security.ServerAuthException;
import org.eclipse.jetty.ee9.security.UserAuthentication;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.security.Constraint;

public class BasicAuthenticator extends LoginAuthenticator
{
    private Charset _charset;

    public Charset getCharset()
    {
        return _charset;
    }

    public void setCharset(Charset charset)
    {
        this._charset = charset;
    }

    @Override
    public String getAuthMethod()
    {
        return Constraint.__BASIC_AUTH;
    }

    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException
    {
        HttpServletRequest request = (HttpServletRequest)req;
        HttpServletResponse response = (HttpServletResponse)res;
        String credentials = request.getHeader(HttpHeader.AUTHORIZATION.asString());

        try
        {
            if (!mandatory)
                return new DeferredAuthentication(this);

            if (credentials != null)
            {
                int space = credentials.indexOf(' ');
                if (space > 0)
                {
                    String method = credentials.substring(0, space);
                    if ("basic".equalsIgnoreCase(method))
                    {
                        credentials = credentials.substring(space + 1);
                        Charset charset = getCharset();
                        if (charset == null)
                            charset = StandardCharsets.ISO_8859_1;
                        credentials = new String(Base64.getDecoder().decode(credentials), charset);
                        int i = credentials.indexOf(':');
                        if (i > 0)
                        {
                            String username = credentials.substring(0, i);
                            String password = credentials.substring(i + 1);

                            UserIdentity user = login(username, password, request);
                            if (user != null)
                                return new UserAuthentication(getAuthMethod(), user);
                        }
                    }
                }
            }

            if (DeferredAuthentication.isDeferred(response))
                return Authentication.UNAUTHENTICATED;

            String value = "basic realm=\"" + _loginService.getName() + "\"";
            Charset charset = getCharset();
            if (charset != null)
                value += ", charset=\"" + charset.name() + "\"";
            response.setHeader(HttpHeader.WWW_AUTHENTICATE.asString(), value);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return Authentication.SEND_CONTINUE;
        }
        catch (IOException e)
        {
            throw new ServerAuthException(e);
        }
    }

    @Override
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser) throws ServerAuthException
    {
        return true;
    }
}
