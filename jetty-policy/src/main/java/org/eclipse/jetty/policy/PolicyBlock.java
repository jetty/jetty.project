package org.eclipse.jetty.policy;

//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.apache.org/licenses/LICENSE-2.0.txt
//
//You may elect to redistribute this code under either of these licenses.
//========================================================================

import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Principal;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Set;

public class PolicyBlock
{
    public CodeSource codesource;
    
    public Set<Certificate> certificates;

    public Principal[] principals;
    
    public PermissionCollection permissions;
    
    private ProtectionDomain protectionDomain;
    
    public ProtectionDomain toProtectionDomain()
    {
        if ( protectionDomain == null )
        {
            // if ( codesource == null )
            // {
            // protectionDomain = new ProtectionDomain( null, permissions );
            // }
            // else
            // {
            protectionDomain = new ProtectionDomain(codesource,null,Thread.currentThread().getContextClassLoader(),principals);

            // protectionDomain = new ProtectionDomain( codesource, permissions, Thread.currentThread().getContextClassLoader(), principals );
            // }
        }
        
        return protectionDomain;
    }

    public CodeSource getCodeSource()
    {
        return codesource;
    }

    public void setCodeSource( CodeSource codesource )
    {
        this.codesource = codesource;
    }

    public Set<Certificate> getCertificates()
    {
        return certificates;
    }

    public void setCertificates( Set<Certificate> certificates )
    {
        this.certificates = certificates;
    }

    public Principal[] getPrincipals()
    {
        return principals;
    }

    public void setPrincipals( Principal[] principals )
    {
        this.principals = principals;
    }

    public PermissionCollection getPermissions()
    {
        return permissions;
    }

    public void setPermissions( PermissionCollection permissions )
    {
        this.permissions = permissions;
    }
    
    
}
