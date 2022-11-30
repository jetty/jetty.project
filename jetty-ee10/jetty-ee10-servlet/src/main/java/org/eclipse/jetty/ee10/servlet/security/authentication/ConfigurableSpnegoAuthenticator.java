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

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.ee10.servlet.security.Authentication;
import org.eclipse.jetty.ee10.servlet.security.Authentication.User;
import org.eclipse.jetty.ee10.servlet.security.ConfigurableSpnegoLoginService;
import org.eclipse.jetty.ee10.servlet.security.ServerAuthException;
import org.eclipse.jetty.ee10.servlet.security.SpnegoUserIdentity;
import org.eclipse.jetty.ee10.servlet.security.SpnegoUserPrincipal;
import org.eclipse.jetty.ee10.servlet.security.UserAuthentication;
import org.eclipse.jetty.ee10.servlet.security.UserIdentity;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.security.Constraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A LoginAuthenticator that uses SPNEGO and the GSS API to authenticate requests.</p>
 * <p>A successful authentication from a client is cached for a configurable
 * {@link #getAuthenticationDuration() duration} using the HTTP session; this avoids
 * that the client is asked to authenticate for every request.</p>
 *
 * @see ConfigurableSpnegoLoginService
 */
public class ConfigurableSpnegoAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurableSpnegoAuthenticator.class);

    private final String _authMethod;
    private Duration _authenticationDuration = Duration.ofNanos(-1);

    public ConfigurableSpnegoAuthenticator()
    {
        this(Constraint.__SPNEGO_AUTH);
    }

    /**
     * Allow for a custom authMethod value to be set for instances where SPNEGO may not be appropriate
     *
     * @param authMethod the auth method
     */
    public ConfigurableSpnegoAuthenticator(String authMethod)
    {
        _authMethod = authMethod;
    }

    @Override
    public String getAuthMethod()
    {
        return _authMethod;
    }

    /**
     * @return the authentication duration
     */
    public Duration getAuthenticationDuration()
    {
        return _authenticationDuration;
    }

    /**
     * <p>Sets the duration of the authentication.</p>
     * <p>A negative duration means that the authentication is only valid for the current request.</p>
     * <p>A zero duration means that the authentication is valid forever.</p>
     * <p>A positive value means that the authentication is valid for the specified duration.</p>
     *
     * @param authenticationDuration the authentication duration
     */
    public void setAuthenticationDuration(Duration authenticationDuration)
    {
        _authenticationDuration = authenticationDuration;
    }

    /**
     * Only renew the session id if the user has been fully authenticated, don't
     * renew the session for any of the intermediate request/response handshakes.
     */
    @Override
    public UserIdentity login(String username, Object password, Request request)
    {
        ServletContextRequest servletContextRequest = Request.as(request, ServletContextRequest.class);
        SpnegoUserIdentity user = (SpnegoUserIdentity)_loginService.login(username, password, servletContextRequest.getServletApiRequest());
        if (user != null && user.isEstablished())
        {
            renewSession(servletContextRequest.getServletApiRequest(), servletContextRequest.getResponse().getServletApiResponse());
        }
        return user;
    }

    @Override
    public Authentication validateRequest(Request req, Response res, Callback callback, boolean mandatory) throws ServerAuthException
    {
        if (!mandatory)
            return new DeferredAuthentication(this);

        ServletContextRequest servletContextRequest = Request.as(req, ServletContextRequest.class);

        String header = req.getHeaders().get(HttpHeader.AUTHORIZATION);
        String spnegoToken = getSpnegoToken(header);
        HttpSession httpSession = servletContextRequest.getServletApiRequest().getSession(false);

        // We have a token from the client, so run the login.
        if (header != null && spnegoToken != null)
        {
            SpnegoUserIdentity identity = (SpnegoUserIdentity)login(null, spnegoToken, req);
            if (identity.isEstablished())
            {
                if (!DeferredAuthentication.isDeferred(res))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Sending final token");
                    // Send to the client the final token so that the
                    // client can establish the GSS context with the server.
                    SpnegoUserPrincipal principal = (SpnegoUserPrincipal)identity.getUserPrincipal();
                    setSpnegoToken(res, principal.getEncodedToken());
                }

                Duration authnDuration = getAuthenticationDuration();
                if (!authnDuration.isNegative())
                {
                    if (httpSession == null)
                        httpSession = servletContextRequest.getServletApiRequest().getSession(true);

                    httpSession.setAttribute(UserIdentityHolder.ATTRIBUTE, new UserIdentityHolder(identity));
                }
                return new UserAuthentication(getAuthMethod(), identity);
            }
            else
            {
                if (DeferredAuthentication.isDeferred(res))
                    return Authentication.UNAUTHENTICATED;
                if (LOG.isDebugEnabled())
                    LOG.debug("Sending intermediate challenge");
                SpnegoUserPrincipal principal = (SpnegoUserPrincipal)identity.getUserPrincipal();
                sendChallenge(req, res, callback, principal.getEncodedToken());
                return Authentication.SEND_CONTINUE;
            }
        }
        // No token from the client; check if the client has logged in
        // successfully before and the authentication has not expired.
        else if (httpSession != null)
        {
            UserIdentityHolder holder = (UserIdentityHolder)httpSession.getAttribute(UserIdentityHolder.ATTRIBUTE);
            if (holder != null)
            {
                UserIdentity identity = holder._userIdentity;
                if (identity != null)
                {
                    Duration authnDuration = getAuthenticationDuration();
                    if (!authnDuration.isNegative())
                    {
                        boolean expired = !authnDuration.isZero() && Instant.now().isAfter(holder._validFrom.plus(authnDuration));
                        // Allow non-GET requests even if they're expired, so that
                        // the client does not need to send the request content again.
                        if (!expired || !HttpMethod.GET.is(req.getMethod()))
                            return new UserAuthentication(getAuthMethod(), identity);
                    }
                }
            }
        }

        if (DeferredAuthentication.isDeferred(res))
            return Authentication.UNAUTHENTICATED;

        if (LOG.isDebugEnabled())
            LOG.debug("Sending initial challenge");
        sendChallenge(req, res, callback, null);
        return Authentication.SEND_CONTINUE;
    }

    private void sendChallenge(Request req, Response res, Callback callback, String token) throws ServerAuthException
    {
        req.accept();
        setSpnegoToken(res, token);
        Response.writeError(req, res, callback, HttpServletResponse.SC_UNAUTHORIZED);
    }

    private void setSpnegoToken(Response response, String token)
    {
        String value = HttpHeader.NEGOTIATE.asString();
        if (token != null)
            value += " " + token;
        response.getHeaders().put(HttpHeader.WWW_AUTHENTICATE.asString(), value);
    }

    private String getSpnegoToken(String header)
    {
        if (header == null)
            return null;
        String scheme = HttpHeader.NEGOTIATE.asString() + " ";
        if (header.regionMatches(true, 0, scheme, 0, scheme.length()))
            return header.substring(scheme.length()).trim();
        return null;
    }

    @Override
    public boolean secureResponse(Request request, Response response, Callback callback, boolean mandatory, User validatedUser)
    {
        return true;
    }

    private static class UserIdentityHolder implements Serializable
    {
        private static final String ATTRIBUTE = UserIdentityHolder.class.getName();

        private final transient Instant _validFrom = Instant.now();
        private final transient UserIdentity _userIdentity;

        private UserIdentityHolder(UserIdentity userIdentity)
        {
            _userIdentity = userIdentity;
        }
    }
}
