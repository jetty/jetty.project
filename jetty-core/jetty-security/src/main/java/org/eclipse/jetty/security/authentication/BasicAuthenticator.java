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

package org.eclipse.jetty.security.authentication;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

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
    public String getAuthenticationType()
    {
        return Authenticator.BASIC_AUTH;
    }

    @Override
    public AuthenticationState validateRequest(Request req, Response res, Callback callback) throws ServerAuthException
    {
        String credentials = req.getHeaders().get(HttpHeader.AUTHORIZATION);

        if (credentials != null)
        {
            int space = credentials.indexOf(' ');
            if (space > 0)
            {
                String method = credentials.substring(0, space);
                if ("Basic".equalsIgnoreCase(method))
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

                        UserIdentity user = login(username, password, req, res);
                        if (user != null)
                            return new UserAuthenticationSucceeded(getAuthenticationType(), user);
                    }
                }
            }
        }

        if (res.isCommitted())
            return null;

        String value = "Basic realm=\"" + _loginService.getName() + "\"";
        Charset charset = getCharset();
        if (charset != null)
            value += ", charset=\"" + charset.name() + "\"";
        res.getHeaders().put(HttpHeader.WWW_AUTHENTICATE.asString(), value);
        Response.writeError(req, res, callback, HttpStatus.UNAUTHORIZED_401);
        return AuthenticationState.CHALLENGE;
    }

    public static String authorization(String user, String password)
    {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.ISO_8859_1));
    }
}
