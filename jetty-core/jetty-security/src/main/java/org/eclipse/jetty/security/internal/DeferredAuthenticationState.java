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

package org.eclipse.jetty.security.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.security.AuthenticationState;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeferredAuthenticationState implements AuthenticationState.Deferred
{
    private static final Logger LOG = LoggerFactory.getLogger(DeferredAuthenticationState.class);
    protected final LoginAuthenticator _authenticator;
    private IdentityService.Association _association;

    public DeferredAuthenticationState(LoginAuthenticator authenticator)
    {
        if (authenticator == null)
            throw new NullPointerException("No Authenticator");
        this._authenticator = authenticator;
    }

    @Override
    public Succeeded authenticate(Request request)
    {
        try
        {
            AuthenticationState authenticationState = _authenticator.validateRequest(request, __deferredResponse, null);
            if (authenticationState != null)
            {
                AuthenticationState.setAuthenticationState(request, authenticationState);
                if (authenticationState instanceof Succeeded succeeded)
                {
                    LoginService loginService = _authenticator.getLoginService();
                    IdentityService identityService = loginService.getIdentityService();

                    if (identityService != null)
                    {
                        UserIdentity user = succeeded.getUserIdentity();
                        _association = identityService.associate(user, null);
                    }

                    return succeeded;
                }
            }
        }
        catch (ServerAuthException e)
        {
            LOG.debug("Unable to authenticate {}", request, e);
        }

        return null;
    }

    @Override
    public AuthenticationState authenticate(Request request, Response response, Callback callback)
    {
        try
        {
            LoginService loginService = _authenticator.getLoginService();
            IdentityService identityService = loginService.getIdentityService();

            AuthenticationState authenticationState = _authenticator.validateRequest(request, response, callback);
            if (authenticationState != null)
            {
                AuthenticationState.setAuthenticationState(request, authenticationState);
                if (authenticationState instanceof Succeeded && identityService != null)
                {
                    UserIdentity user = ((Succeeded)authenticationState).getUserIdentity();
                    _association = identityService.associate(user, null);
                }
            }
            return authenticationState;
        }
        catch (ServerAuthException e)
        {
            LOG.debug("Unable to authenticate {}", request, e);
        }
        return null;
    }

    @Override
    public Succeeded login(String username, Object password, Request request, Response response)
    {
        if (username == null)
            return null;

        UserIdentity identity = _authenticator.login(username, password, request, response);
        if (identity != null)
        {
            IdentityService identityService = _authenticator.getLoginService().getIdentityService();
            AuthenticationState.Succeeded authentication = new LoginAuthenticator.UserAuthenticationSucceeded("API", identity);
            if (identityService != null)
                _association = identityService.associate(identity, null);
            return authentication;
        }
        return null;
    }

    @Override
    public void logout(Request request, Response response)
    {
        _authenticator.logout(request, response);
    }

    @Override
    public IdentityService.Association getAssociation()
    {
        return _association;
    }

    static final Response __deferredResponse = new Deferred.DeferredResponse()
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
        public Supplier<HttpFields> getTrailersSupplier()
        {
            return null;
        }

        @Override
        public void setTrailersSupplier(Supplier<HttpFields> trailers)
        {
        }

        @Override
        public void write(boolean last, ByteBuffer content, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public boolean isCommitted()
        {
            return true;
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

        @Override
        public CompletableFuture<Void> writeInterim(int status, HttpFields headers)
        {
            return null;
        }
    };
}
