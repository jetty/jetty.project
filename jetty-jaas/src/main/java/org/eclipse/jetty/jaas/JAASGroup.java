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
import java.util.HashSet;
import java.util.Iterator;

public class JAASGroup implements Group 
{
    public static final String ROLES = "__roles__";
    
    private String _name = null;
    private HashSet<Principal> _members = null;
   
    public JAASGroup(String n)
    {
        this._name = n;
        this._members = new HashSet<Principal>();
    }
   
    /* ------------------------------------------------------------ */
    public synchronized boolean addMember(Principal principal)
    {
        return _members.add(principal);
    }

    public synchronized boolean removeMember(Principal principal)
    {
        return _members.remove(principal);
    }

    public boolean isMember(Principal principal)
    {
        return _members.contains(principal);
    }

    public Enumeration<? extends Principal> members()
    {

        class MembersEnumeration implements Enumeration<Principal>
        {
            private Iterator<? extends Principal> itor;
            
            public MembersEnumeration (Iterator<? extends Principal> itor)
            {
                this.itor = itor;
            }
            
            public boolean hasMoreElements ()
            {
                return this.itor.hasNext();
            }


            public Principal nextElement ()
            {
                return this.itor.next();
            }
            
        }

        return new MembersEnumeration (_members.iterator());
    }

    public int hashCode()
    {
        return getName().hashCode();
    }

    public boolean equals(Object object)
    {
        if (! (object instanceof JAASGroup))
            return false;

        return ((JAASGroup)object).getName().equals(getName());
    }

    public String toString()
    {
        return getName();
    }

    public String getName()
    {
        
        return _name;
    }
}
