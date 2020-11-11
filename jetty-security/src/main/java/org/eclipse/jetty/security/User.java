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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.security.Credential;

class User
{
    protected UserPrincipal _userPrincipal;
    protected List<RolePrincipal> _rolePrincipals = Collections.emptyList();
    
    protected User(String username, Credential credential, String[] roles)
    {
        _userPrincipal = new UserPrincipal(username, credential);

        _rolePrincipals = Collections.emptyList();
        
        if (roles != null)
            _rolePrincipals = Arrays.stream(roles).map(RolePrincipal::new).collect(Collectors.toList());
    }
    
    protected UserPrincipal getUserPrincipal()
    {
        return _userPrincipal;
    }
    
    protected List<RolePrincipal> getRolePrincipals()
    {
        return _rolePrincipals;
    }
}
