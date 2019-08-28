//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.security.Principal;
import javax.security.auth.Subject;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class OpenIdLoginService extends ContainerLifeCycle implements LoginService
{
    private static final Logger LOG = Log.getLogger(OpenIdLoginService.class);

    private final OpenIdConfiguration _configuration;
    private final LoginService loginService;
    private IdentityService identityService;

    public OpenIdLoginService(OpenIdConfiguration configuration)
    {
        this(configuration, null);
    }

    public OpenIdLoginService(OpenIdConfiguration configuration, LoginService loginService)
    {
        _configuration = configuration;
        this.loginService = loginService;
        addBean(this.loginService);
    }

    @Override
    public String getName()
    {
        return _configuration.getIdentityProvider();
    }

    public OpenIdConfiguration getConfiguration()
    {
        return _configuration;
    }

    @Override
    public UserIdentity login(String identifier, Object credentials, ServletRequest req)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("login({}, {}, {})", identifier, credentials, req);

        OpenIdCredentials openIdCredentials = (OpenIdCredentials)credentials;
        try
        {
            openIdCredentials.redeemAuthCode();
            if (!openIdCredentials.validate())
                return null;
        }
        catch (IOException e)
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
                return null;

            return new OpenIdUserIdentity(subject, userPrincipal, userIdentity);
        }

        return identityService.newUserIdentity(subject, userPrincipal, new String[0]);
    }

    @Override
    public boolean validate(UserIdentity user)
    {
        Principal userPrincipal = user.getUserPrincipal();
        if (!(userPrincipal instanceof OpenIdUserPrincipal))
            return false;

        OpenIdCredentials credentials = ((OpenIdUserPrincipal)userPrincipal).getCredentials();
        return credentials.validate();
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
    }
}
