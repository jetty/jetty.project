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

package org.eclipse.jetty.jaas;

public class JAASRole extends JAASPrincipal
{
    private static final long serialVersionUID = 3465114254970134526L;

    public JAASRole(String name)
    {
        super(name);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof JAASRole))
            return false;

        return getName().equals(((JAASRole)o).getName());
    }
}
