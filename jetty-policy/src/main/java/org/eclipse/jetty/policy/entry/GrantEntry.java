package org.eclipse.jetty.policy.entry;
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

import java.net.URL;
import java.security.CodeSource;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;

public class GrantEntry extends AbstractEntry
{

    /**
     * The signers part of grant clause. This is a comma-separated list of certificate aliases.
     */
    private String signers;

    /**
     * The codebase part of grant clause. This is an URL from which code originates.
     */
    private String codebase;

    /**
     * Collection of PrincipalEntries of grant clause.
     */
    private Collection<PrincipalEntry> principalNodes;

    /**
     * Collection of PermissionEntries of grant clause.
     */
    private Collection<PermissionEntry> permissionNodes;

    // cached permissions
    private PermissionCollection permissions;
    private Certificate[] signerArray;
    private CodeSource codesource;
    private Principal[] principals;
    
    /**
     * Adds specified element to the <code>principals</code> collection. If collection does not exist yet, creates a
     * new one.
     */
    public void addPrincipal( PrincipalEntry pe )
    {
        if ( principalNodes == null )
        {
            principalNodes = new HashSet<PrincipalEntry>();
        }
        principalNodes.add( pe );
    }

    public void expand( PolicyContext context ) throws PolicyException
    {
        if ( signers != null )
        {
            signerArray = resolveToCertificates( context.getKeystore(), signers );  // TODO alter to support self:: etc
        }
        codebase = context.evaluate( codebase );
        
        if ( principalNodes != null )
        {
            Set<Principal> principalSet = new HashSet<Principal>();
            for ( Iterator<PrincipalEntry> i = principalNodes.iterator(); i.hasNext(); )
            {
                PrincipalEntry node = i.next();
                node.expand( context );
                principalSet.add( node.toPrincipal( context ) );
            }
            principals = principalSet.toArray( new Principal[principalSet.size()] );
        }
        
        context.setPrincipals( principals );
        permissions = new Permissions();
        for ( Iterator<PermissionEntry> i = permissionNodes.iterator(); i.hasNext(); )
        {
            PermissionEntry node = i.next();
            node.expand( context );
            permissions.add( node.toPermission() );
        }
        context.setPrincipals( null );
        
        setExpanded( true );
    }    
    
    public PermissionCollection getPermissions() throws PolicyException
    {
        return permissions;
    }    
    
    public Principal[] getPrincipals() throws PolicyException
    {
        return principals;
    }
    
    public CodeSource getCodeSource() throws PolicyException
    {
        if ( !isExpanded() )
        {
            throw new PolicyException("GrantNode needs to be expanded.");
        }
        
        try
        {
            if ( codesource == null && codebase != null )
            {
                URL url = new URL( codebase );
                codesource = new CodeSource( url, signerArray );
            }

            return codesource;
        }
        catch ( Exception e )
        {
            throw new PolicyException( e );
        }
    }
    
    /**
     * resolve signers into an array of certificates using a given keystore
     * 
     * @param keyStore
     * @param signers
     * @return
     * @throws Exception
     */
    private Certificate[] resolveToCertificates( KeyStore keyStore, String signers ) throws PolicyException
    {               
        if ( keyStore == null )
        {
            Certificate[] certs = null;
            return certs;
        }
                
        Set<Certificate> certificateSet = new HashSet<Certificate>();       
        StringTokenizer strTok = new StringTokenizer( signers, ",");
        
        for ( int i = 0; strTok.hasMoreTokens(); ++i )
        {
            try
            {               
                Certificate certificate = keyStore.getCertificate( strTok.nextToken().trim() );
                
                if ( certificate != null )
                {
                    certificateSet.add( certificate );
                }               
            }
            catch ( KeyStoreException kse )
            {
                throw new PolicyException( kse );
            }
        }
        
        return certificateSet.toArray( new Certificate[certificateSet.size()] );
    }
    

    public void setSigners( String signers )
    {
        this.signers = signers;
    }

    public void setCodebase( String codebase )
    {
        this.codebase = codebase;
    }

    public void setPrincipals( Collection<PrincipalEntry> principals )
    {
        this.principalNodes = principals;
    }

    public void setPermissions( Collection<PermissionEntry> permissions )
    {
        this.permissionNodes = permissions;
    }

    
    
}
