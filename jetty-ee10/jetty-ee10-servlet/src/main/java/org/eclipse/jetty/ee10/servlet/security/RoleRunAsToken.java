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

/**
 * @version $Rev: 4701 $ $Date: 2009-03-03 13:01:26 +0100 (Tue, 03 Mar 2009) $
 */
public class RoleRunAsToken implements RunAsToken
{
    private final String _runAsRole;

    public RoleRunAsToken(String runAsRole)
    {
        this._runAsRole = runAsRole;
    }

    public String getRunAsRole()
    {
        return _runAsRole;
    }

    @Override
    public String toString()
    {
        return "RoleRunAsToken(" + _runAsRole + ")";
    }
}
