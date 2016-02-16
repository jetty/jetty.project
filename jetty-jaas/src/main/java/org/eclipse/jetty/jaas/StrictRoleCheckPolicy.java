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

package org.eclipse.jetty.jaas;

import java.security.Principal;
import java.security.acl.Group;
import java.util.Enumeration;


/* ---------------------------------------------------- */
/** StrictRoleCheckPolicy
 * <p>Enforces that if a runAsRole is present, then the
 * role to check must be the same as that runAsRole and
 * the set of static roles is ignored.
 * 
 *
 * 
 */
public class StrictRoleCheckPolicy implements RoleCheckPolicy
{

    public boolean checkRole (String roleName, Principal runAsRole, Group roles)
    {
        //check if this user has had any temporary role pushed onto
        //them. If so, then only check if the user has that role.
        if (runAsRole != null)
        {
            return (roleName.equals(runAsRole.getName()));
        }
        else
        {
            if (roles == null)
                return false;
            Enumeration<? extends Principal> rolesEnum = roles.members();
            boolean found = false;
            while (rolesEnum.hasMoreElements() && !found)
            {
                Principal p = (Principal)rolesEnum.nextElement();
                found = roleName.equals(p.getName());
            }
            return found;
        }
        
    }
    
}
