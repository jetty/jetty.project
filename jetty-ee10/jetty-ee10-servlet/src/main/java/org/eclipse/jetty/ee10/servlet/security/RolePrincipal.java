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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.Serializable;
import java.security.Principal;
import javax.security.auth.Subject;

/**
 * RolePrincipal
 * 
 * Represents a role. This class can be added to a Subject to represent a role that the
 * Subject has.
 * 
 */
public class RolePrincipal implements Principal, Serializable
{
    private static final long serialVersionUID = 2998397924051854402L;
    private final String _roleName;

    public RolePrincipal(String name)
    {
        _roleName = name;
    }

    @Override
    public String getName()
    {
        return _roleName;
    }
    
    public void configureForSubject(Subject subject)
    {
        subject.getPrincipals().add(this);
    }
}
