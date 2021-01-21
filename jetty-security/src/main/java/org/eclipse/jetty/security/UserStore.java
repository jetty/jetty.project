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

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.auth.Subject;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.security.Credential;

/**
 * Base class to store User
 */
public class UserStore extends AbstractLifeCycle
{
    private IdentityService _identityService = new DefaultIdentityService();
    private final Map<String, UserIdentity> _knownUserIdentities = new ConcurrentHashMap<>();

    public void addUser(String username, Credential credential, String[] roles)
    {
        Principal userPrincipal = new AbstractLoginService.UserPrincipal(username, credential);
        Subject subject = new Subject();
        subject.getPrincipals().add(userPrincipal);
        subject.getPrivateCredentials().add(credential);

        if (roles != null)
        {
            for (String role : roles)
            {
                subject.getPrincipals().add(new AbstractLoginService.RolePrincipal(role));
            }
        }

        subject.setReadOnly();
        _knownUserIdentities.put(username, _identityService.newUserIdentity(subject, userPrincipal, roles));
    }

    public void removeUser(String username)
    {
        _knownUserIdentities.remove(username);
    }

    public UserIdentity getUserIdentity(String userName)
    {
        return _knownUserIdentities.get(userName);
    }

    public IdentityService getIdentityService()
    {
        return _identityService;
    }

    public Map<String, UserIdentity> getKnownUserIdentities()
    {
        return _knownUserIdentities;
    }
}
