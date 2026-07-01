//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.security;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;

public class NamePrincipal implements Principal, Serializable
{
    @Serial
    private static final long serialVersionUID = 3592383324053985402L;

    private final String _name;

    public NamePrincipal(String name)
    {
        _name = name;
    }

    @Override
    public String getName()
    {
        return _name;
    }

    @Override
    public String toString()
    {
        return getName();
    }
}