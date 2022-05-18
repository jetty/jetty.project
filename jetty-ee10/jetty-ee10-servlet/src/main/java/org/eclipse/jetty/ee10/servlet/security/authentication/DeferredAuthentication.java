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

package org.eclipse.jetty.ee10.servlet.security.authentication;

import java.nio.ByteBuffer;

import org.eclipse.jetty.ee10.servlet.security.Authentication;
import org.eclipse.jetty.ee10.servlet.security.IdentityService;
import org.eclipse.jetty.ee10.servlet.security.LoggedOutAuthentication;
import org.eclipse.jetty.ee10.servlet.security.LoginService;
import org.eclipse.jetty.ee10.servlet.security.SecurityHandler;
import org.eclipse.jetty.ee10.servlet.security.ServerAuthException;
import org.eclipse.jetty.ee10.servlet.security.UserAuthentication;
import org.eclipse.jetty.ee10.servlet.security.UserIdentity;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeferredAuthentication implements Authentication.Deferred
{
    private static final Logger LOG = LoggerFactory.getLogger(DeferredAuthentication.class);
    protected final LoginAuthenticator _authenticator;
    private Object _previousAssociation;

    public DeferredAuthentication(LoginAuthenticator authenticator)
    {
        if (authenticator == null)
            throw new NullPointerException("No Authenticator");
        this._authenticator = authenticator;
    }

    @Override
    public Authentication authenticate(Request request)
    {
        try
        {
            Authentication authentication = _authenticator.validateRequest(request, __deferredResponse, null, true);
            if (authentication != null && (authentication instanceof Authentication.User) && !(authentication instanceof Authentication.ResponseSent))
            {
                LoginService loginService = _authenticator.getLoginService();
                IdentityService identityService = loginService.getIdentityService();

                if (identityService != null)
                    _previousAssociation = identityService.associate(((Authentication.User)authentication).getUserIdentity());

                return authentication;
            }
        }
        catch (ServerAuthException e)
        {
            LOG.debug("Unable to authenticate {}", request, e);
        }

        return this;
    }

    @Override
    public Authentication authenticate(Request request, Response response)
    {
        try
        {
            LoginService loginService = _authenticator.getLoginService();
            IdentityService identityService = loginService.getIdentityService();

            Authentication authentication = _authenticator.validateRequest(request, response, null, true);
            if (authentication instanceof Authentication.User && identityService != null)
                _previousAssociation = identityService.associate(((Authentication.User)authentication).getUserIdentity());
            return authentication;
        }
        catch (ServerAuthException e)
        {
            LOG.debug("Unable to authenticate {}", request, e);
        }
        return this;
    }

    @Override
    public Authentication login(String username, Object password, Request request)
    {
        if (username == null)
            return null;

        UserIdentity identity = _authenticator.login(username, password, request);
        if (identity != null)
        {
            IdentityService identityService = _authenticator.getLoginService().getIdentityService();
            UserAuthentication authentication = new UserAuthentication("API", identity);
            if (identityService != null)
                _previousAssociation = identityService.associate(identity);
            return authentication;
        }
        return null;
    }

    @Override
    public Authentication logout(Request request)
    {
        SecurityHandler security = SecurityHandler.getCurrentSecurityHandler();
        if (security != null)
        {
            security.logout(null);
            if (_authenticator instanceof LoginAuthenticator)
            {
                ((LoginAuthenticator)_authenticator).logout(request);
                return new LoggedOutAuthentication((LoginAuthenticator)_authenticator);
            }
        }

        return Authentication.UNAUTHENTICATED;
    }

    public Object getPreviousAssociation()
    {
        return _previousAssociation;
    }

    /**
     * @param response the response
     * @return true if this response is from a deferred call to {@link #authenticate(Request)}
     */
    public static boolean isDeferred(Response response)
    {
        return response == __deferredResponse;
    }

    private static final Response __deferredResponse = new Response()
    {
        @Override
        public Request getRequest()
        {
            return null;
        }

        @Override
        public int getStatus()
        {
            return 0;
        }

        @Override
        public void setStatus(int code)
        {
        }

        @Override
        public Mutable getHeaders()
        {
            return null;
        }

        @Override
        public Mutable getOrCreateTrailers()
        {
            return null;
        }

        @Override
        public void write(Content.Chunk chunk, Callback callback)
        {
        }

        @Override
        public void write(boolean last, Callback callback, ByteBuffer... content)
        {
        }

        @Override
        public boolean isCommitted()
        {
            return false;
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            return false;
        }

        @Override
        public void reset()
        {
        }
    };
}
