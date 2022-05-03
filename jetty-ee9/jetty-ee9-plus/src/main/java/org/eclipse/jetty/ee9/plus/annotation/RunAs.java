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

package org.eclipse.jetty.ee9.plus.annotation;

import java.util.Objects;

import org.eclipse.jetty.ee9.servlet.ServletHolder;

/**
 * RunAs
 * <p>
 * Represents a <code>&lt;run-as&gt;</code> element in web.xml, or a <code>&#064;RunAs</code> annotation.
 * @deprecated unused as of 9.4.28 due for removal in 10.0.0
 */
@Deprecated
public class RunAs
{
    private String _className;
    private String _roleName;

    public RunAs(String className, String roleName)
    {
        _className = Objects.requireNonNull(className);
        _roleName = Objects.requireNonNull(roleName);
    }

    public String getTargetClassName()
    {
        return _className;
    }

    public String getRoleName()
    {
        return _roleName;
    }

    public void setRunAs(ServletHolder holder)
    {
        if (holder == null)
            return;
        String className = holder.getClassName();

        if (className.equals(_className))
        {
            //Only set the RunAs if it has not already been set, presumably by web/web-fragment.xml
            if (holder.getRegistration().getRunAsRole() == null)
                holder.getRegistration().setRunAsRole(_roleName);
        }
    }
}
