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

public interface RoleCheckPolicy 
{
    /* ------------------------------------------------ */
    /** Check if a role is either a runAsRole or in a set of roles
     * @param roleName the role to check
     * @param runAsRole a pushed role (can be null)
     * @param roles a Group whose Principals are role names
     * @return <code>true</code> if <code>role</code> equals <code>runAsRole</code> or is a member of <code>roles</code>.
     */
    public boolean checkRole (String roleName, Principal runAsRole, Group roles);
    
}
