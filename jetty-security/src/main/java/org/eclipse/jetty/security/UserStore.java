//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
