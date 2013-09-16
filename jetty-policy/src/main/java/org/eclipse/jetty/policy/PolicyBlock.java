//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.policy;

import java.security.CodeSource;
import java.security.KeyStore;
import java.security.PermissionCollection;
import java.security.Principal;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Set;

public class PolicyBlock
{
    public CodeSource codesource;
    
    public KeyStore keyStore;
    
    public Set<Certificate> certificates;

    public Principal[] principals;
    
    public PermissionCollection permissions;
    
    private ProtectionDomain protectionDomain;
    
    public ProtectionDomain toProtectionDomain()
    {
        if ( protectionDomain == null )
        {
            protectionDomain = new ProtectionDomain(codesource,null,Thread.currentThread().getContextClassLoader(),principals);
        }
                
        return protectionDomain;
    }
   
    public KeyStore getKeyStore()
    {
        return keyStore;
    }

    public void setKeyStore(KeyStore keyStore)
    {
        this.keyStore = keyStore;
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
