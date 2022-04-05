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

import java.security.Principal;
import javax.security.auth.Subject;

import org.eclipse.jetty.ee10.security.IdentityService;

/**
 * This is similar to the jaspi PasswordValidationCallback but includes user
 * principal and group info as well.
 *
 * @version $Rev: 4793 $ $Date: 2009-03-19 00:00:01 +0100 (Thu, 19 Mar 2009) $
 */
public class LoginCallbackImpl implements LoginCallback
{
    // initial data
    private final Subject subject;

    private final String userName;

    private Object credential;

    private boolean success;

    private Principal userPrincipal;

    private String[] roles = IdentityService.NO_ROLES;

    //TODO could use Credential instance instead of Object if Basic/Form create a Password object
    public LoginCallbackImpl(Subject subject, String userName, Object credential)
    {
        this.subject = subject;
        this.userName = userName;
        this.credential = credential;
    }

    @Override
    public Subject getSubject()
    {
        return subject;
    }

    @Override
    public String getUserName()
    {
        return userName;
    }

    @Override
    public Object getCredential()
    {
        return credential;
    }

    @Override
    public boolean isSuccess()
    {
        return success;
    }

    @Override
    public void setSuccess(boolean success)
    {
        this.success = success;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return userPrincipal;
    }

    @Override
    public void setUserPrincipal(Principal userPrincipal)
    {
        this.userPrincipal = userPrincipal;
    }

    @Override
    public String[] getRoles()
    {
        return roles;
    }

    @Override
    public void setRoles(String[] groups)
    {
        this.roles = groups;
    }

    @Override
    public void clearPassword()
    {
        if (credential != null)
        {
            credential = null;
        }
    }
}
