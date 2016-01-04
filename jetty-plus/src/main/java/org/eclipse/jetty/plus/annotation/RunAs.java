//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.annotation;

import org.eclipse.jetty.servlet.ServletHolder;

/**
 * RunAs
 * <p>
 * Represents a <code>&lt;run-as&gt;</code> element in web.xml, or a <code>&#064;RunAs</code> annotation.
 */
public class RunAs
{
    private String _className;
    private String _roleName;

    public RunAs()
    {}


    public void setTargetClassName (String className)
    {
        _className = className;
    }
    
    public String getTargetClassName()
    {
        return _className;
    }

    public void setRoleName (String roleName)
    {
        _roleName = roleName;
    }

    public String getRoleName ()
    {
        return _roleName;
    }

    public void setRunAs (ServletHolder holder)
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
