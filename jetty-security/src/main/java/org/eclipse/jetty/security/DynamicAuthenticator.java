//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.security;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;

/**
 * Used as a mapping of {@link org.eclipse.jetty.http.pathmap.PathSpec} to {@link Authenticator}.
 *
 * This allows you to register different authentication mechanisms for different paths.
 */
public class DynamicAuthenticator extends ContainerLifeCycle implements Authenticator
{
    private static final Logger LOG = Log.getLogger(DynamicAuthenticator.class);

    private final PathMappings<AuthenticatorEntry> _mappings = new PathMappings<>();

    public void addMapping(String pathSpec, Authenticator authenticator)
    {
        addMapping(pathSpec, new AuthenticatorEntry(authenticator));
    }

    public void addMapping(String pathSpec, Authenticator authenticator, LoginService loginService)
    {
        addMapping(pathSpec, new AuthenticatorEntry(authenticator, loginService));
    }

    public void addMapping(String pathSpec, Authenticator authenticator, LoginService loginService, IdentityService identityService)
    {
        addMapping(pathSpec, new AuthenticatorEntry(authenticator, loginService, identityService));
    }

    private void addMapping(String pathSpec, AuthenticatorEntry entry)
    {
        _mappings.put(new ServletPathSpec(pathSpec), entry);
        addBean(entry);
    }

    @Override
    public void setConfiguration(AuthConfiguration configuration)
    {
        for (MappedResource<AuthenticatorEntry> mapping : _mappings)
        {
            AuthenticatorEntry entry = mapping.getResource();
            DynamicConfiguration config = new DynamicConfiguration(configuration, entry);
            entry.getAuthenticator().setConfiguration(config);
        }
    }

    @Override
    public String getAuthMethod()
    {
        return Constraint.__DYNAMIC_AUTH;
    }

    @Override
    public void prepareRequest(ServletRequest request)
    {
        Authenticator authenticator = getAuthenticator(request);
        if (authenticator == null)
            return;

        authenticator.prepareRequest(request);
    }

    @Override
    public Authentication validateRequest(ServletRequest request, ServletResponse response, boolean mandatory) throws ServerAuthException
    {
        final Request baseRequest = Objects.requireNonNull(Request.getBaseRequest(request));
        final Response baseResponse = baseRequest.getResponse();

        Authenticator authenticator = getAuthenticator(request);
        if (authenticator == null)
        {
            try
            {
                baseResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
                return Authentication.SEND_FAILURE;
            }
            catch (IOException e)
            {
                throw new ServerAuthException(e);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Forwarding request {} to {}", request, authenticator);

        return authenticator.validateRequest(request, response, mandatory);
    }

    @Override
    public boolean secureResponse(ServletRequest request, ServletResponse response, boolean mandatory, Authentication.User validatedUser) throws ServerAuthException
    {
        Authenticator authenticator = getAuthenticator(request);
        if (authenticator == null)
            return true;

        return authenticator.secureResponse(request, response, mandatory, validatedUser);
    }

    private Authenticator getAuthenticator(ServletRequest request)
    {
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        String pathInContext = URIUtil.addPaths(httpRequest.getServletPath(), httpRequest.getPathInfo());
        MappedResource<AuthenticatorEntry> match = _mappings.getMatch(pathInContext == null ? "" : pathInContext);
        if (match == null)
            return null;

        return match.getResource().getAuthenticator();
    }

    /**
     * An entry containing an {@link Authenticator}.
     *
     * A specific {@link LoginService} and {@link IdentityService} may be set per {@link AuthenticatorEntry}.
     * If these values are not set then the values from the {@link AuthConfiguration} will be used, however if they
     * are specifically set, then that value will always be used, even if it is a null value.
     */
    private static class AuthenticatorEntry extends ContainerLifeCycle
    {
        private final Authenticator authenticator;
        private final boolean hasLoginService;
        private final LoginService loginService;
        private final boolean hasIdentityService;
        private final IdentityService identityService;

        public AuthenticatorEntry(Authenticator authenticator)
        {
            this.authenticator = Objects.requireNonNull(authenticator);
            this.loginService = null;
            this.identityService = null;
            this.hasLoginService = false;
            this.hasIdentityService = false;
            addBean(authenticator);
        }

        public AuthenticatorEntry(Authenticator authenticator, LoginService loginService)
        {
            this.authenticator = Objects.requireNonNull(authenticator);
            this.loginService = loginService;
            this.identityService = null;
            this.hasLoginService = true;
            this.hasIdentityService = false;
            addBean(authenticator);
            addBean(loginService);
        }

        public AuthenticatorEntry(Authenticator authenticator, LoginService loginService, IdentityService identityService)
        {
            this.authenticator = Objects.requireNonNull(authenticator);
            this.loginService = loginService;
            this.identityService = identityService;
            this.hasLoginService = true;
            this.hasIdentityService = true;
            addBean(authenticator);
            addBean(loginService);
            addBean(identityService);
        }

        public Authenticator getAuthenticator()
        {
            return authenticator;
        }

        public LoginService getLoginService()
        {
            return loginService;
        }

        public IdentityService getIdentityService()
        {
            return identityService;
        }

        public boolean hasLoginService()
        {
            return hasLoginService;
        }

        public boolean hasIdentityService()
        {
            return hasIdentityService;
        }

        @Override
        public String toString()
        {
            return String.format("%s{%s,%s,%s}", getClass().getSimpleName(), authenticator, loginService, identityService);
        }
    }

    private static class DynamicConfiguration implements AuthConfiguration
    {
        private final AuthConfiguration configuration;
        private final LoginService loginService;
        private final IdentityService identityService;

        public DynamicConfiguration(AuthConfiguration configuration, AuthenticatorEntry entry)
        {
            this.configuration = configuration;
            this.loginService = entry.hasLoginService() ? entry.getLoginService() : configuration.getLoginService();
            this.identityService = entry.hasIdentityService() ? entry.getIdentityService() : configuration.getIdentityService();
        }

        @Override
        public String getAuthMethod()
        {
            return configuration.getAuthMethod();
        }

        @Override
        public String getRealmName()
        {
            return configuration.getRealmName();
        }

        @Override
        public String getInitParameter(String param)
        {
            return configuration.getInitParameter(param);
        }

        @Override
        public Set<String> getInitParameterNames()
        {
            return configuration.getInitParameterNames();
        }

        @Override
        public LoginService getLoginService()
        {
            return loginService;
        }

        @Override
        public IdentityService getIdentityService()
        {
            return identityService;
        }

        @Override
        public boolean isSessionRenewedOnAuthentication()
        {
            return configuration.isSessionRenewedOnAuthentication();
        }
    }
}
