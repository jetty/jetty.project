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

package org.eclipse.jetty.security.openid;

import javax.security.auth.Subject;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * The implementation of {@link LoginService} required to use OpenID Connect.
 *
 * <p>
 * Can contain an optional wrapped {@link LoginService} which is used to store role information about users.
 * </p>
 */
public class OpenIdLoginService extends ContainerLifeCycle implements LoginService
{
    private static final Logger LOG = Log.getLogger(OpenIdLoginService.class);

    private final OpenIdConfiguration configuration;
    private final LoginService loginService;
    private IdentityService identityService;
    private boolean authenticateNewUsers;

    public OpenIdLoginService(OpenIdConfiguration configuration)
    {
        this(configuration, null);
    }

    /**
     * Use a wrapped {@link LoginService} to store information about user roles.
     * Users in the wrapped loginService must be stored with their username as
     * the value of the sub (subject) Claim, and a credentials value of the empty string.
     * @param configuration the OpenID configuration to use.
     * @param loginService the wrapped LoginService to defer to for user roles.
     */
    public OpenIdLoginService(OpenIdConfiguration configuration, LoginService loginService)
    {
        this.configuration = configuration;
        this.loginService = loginService;
        addBean(this.configuration);
        addBean(this.loginService);
    }

    @Override
    public String getName()
    {
        return configuration.getIssuer();
    }

    public OpenIdConfiguration getConfiguration()
    {
        return configuration;
    }

    @Override
    public UserIdentity login(String identifier, Object credentials, ServletRequest req)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("login({}, {}, {})", identifier, credentials, req);

        OpenIdCredentials openIdCredentials = (OpenIdCredentials)credentials;
        try
        {
            openIdCredentials.redeemAuthCode(configuration);
        }
        catch (Throwable e)
        {
            LOG.warn(e);
            return null;
        }

        OpenIdUserPrincipal userPrincipal = new OpenIdUserPrincipal(openIdCredentials);
        Subject subject = new Subject();
        subject.getPrincipals().add(userPrincipal);
        subject.getPrivateCredentials().add(credentials);
        subject.setReadOnly();

        if (loginService != null)
        {
            UserIdentity userIdentity = loginService.login(openIdCredentials.getUserId(), "", req);
            if (userIdentity == null)
            {
                if (isAuthenticateNewUsers())
                    return getIdentityService().newUserIdentity(subject, userPrincipal, new String[0]);
                return null;
            }
            return new OpenIdUserIdentity(subject, userPrincipal, userIdentity);
        }

        return identityService.newUserIdentity(subject, userPrincipal, new String[0]);
    }

    public boolean isAuthenticateNewUsers()
    {
        return authenticateNewUsers;
    }

    /**
     * This setting is only meaningful if a wrapped {@link LoginService} has been set.
     * <p>
     * If set to true, any users not found by the wrapped {@link LoginService} will still
     * be authenticated but with no roles, if set to false users will not be
     * authenticated unless they are discovered by the wrapped {@link LoginService}.
     * </p>
     * @param authenticateNewUsers whether to authenticate users not found by a wrapping LoginService
     */
    public void setAuthenticateNewUsers(boolean authenticateNewUsers)
    {
        this.authenticateNewUsers = authenticateNewUsers;
    }

    @Override
    public boolean validate(UserIdentity user)
    {
        if (!(user.getUserPrincipal() instanceof OpenIdUserPrincipal))
            return false;

        return loginService == null || loginService.validate(user);
    }

    @Override
    public IdentityService getIdentityService()
    {
        return loginService == null ? identityService : loginService.getIdentityService();
    }

    @Override
    public void setIdentityService(IdentityService service)
    {
        if (isRunning())
            throw new IllegalStateException("Running");

        if (loginService != null)
            loginService.setIdentityService(service);
        else
            identityService = service;
    }

    @Override
    public void logout(UserIdentity user)
    {
        if (loginService != null)
            loginService.logout(user);
    }
}
