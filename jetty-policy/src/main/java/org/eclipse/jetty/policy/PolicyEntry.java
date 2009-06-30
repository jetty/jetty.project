package org.eclipse.jetty.policy;

import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Principal;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Set;

public class PolicyEntry
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
            if ( codesource == null )
            {
                protectionDomain = new ProtectionDomain( null, permissions );
            }
            else
            {   
                protectionDomain = new ProtectionDomain( codesource, permissions, Thread.currentThread().getContextClassLoader(), principals );
            }
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
