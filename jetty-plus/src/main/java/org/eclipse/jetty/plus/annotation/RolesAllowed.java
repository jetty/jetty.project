// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.plus.annotation;

/**
 * RolesAllowed
 *
 *
 */
public class RolesAllowed extends AbstractAccessControl
{
    protected String[] _roles;
    
    public RolesAllowed(String className)
    {
        super(className);
    }

    public void setRoles (String[] roles)
    {
        if (roles != null)
        {
            _roles = new String[roles.length];
            System.arraycopy(roles, 0, _roles, 0, roles.length);
        }
    }
    
    public String[] getRoles ()
    {
        return _roles;
    }
}
