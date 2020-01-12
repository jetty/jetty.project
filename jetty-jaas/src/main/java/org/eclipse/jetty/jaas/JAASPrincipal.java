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

package org.eclipse.jetty.jaas;

import java.io.Serializable;
import java.security.Principal;

/**
 * JAASPrincipal
 * <p>
 * Impl class of Principal interface.
 */
public class JAASPrincipal implements Principal, Serializable
{
    private static final long serialVersionUID = -5538962177019315479L;

    private final String _name;

    public JAASPrincipal(String userName)
    {
        this._name = userName;
    }

    @Override
    public boolean equals(Object p)
    {
        if (!(p instanceof JAASPrincipal))
            return false;

        return getName().equals(((JAASPrincipal)p).getName());
    }

    @Override
    public int hashCode()
    {
        return getName().hashCode();
    }

    @Override
    public String getName()
    {
        return this._name;
    }

    @Override
    public String toString()
    {
        return getName();
    }
}

    
