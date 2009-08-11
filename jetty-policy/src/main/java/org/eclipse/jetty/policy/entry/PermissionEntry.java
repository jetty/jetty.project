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

import java.lang.reflect.Constructor;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Permission;
import java.security.cert.Certificate;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;

public class PermissionEntry extends AbstractEntry
{
    /**
     * The classname part of permission clause.
     */
    private String klass;

    /**
     * The name part of permission clause.
     */
    private String name;

    /**
     * The actions part of permission clause.
     */
    private String actions;

    /**
     * The signers part of permission clause. This is a comma-separated list of certificate aliases.
     */
    private String signers;
    
    
    private Certificate[] signerArray;
    
    public Permission toPermission() throws PolicyException
    {
        try
        {
            Class<?> clazz = Class.forName(klass);
            
            if ( signerArray != null && !validate( signerArray, (Certificate[])clazz.getSigners() ) )
            {
                throw new PolicyException( "Unvalidated Permissions: " + klass + "/" + name );
            }
            
            Permission permission = null;

            if ( name == null && actions == null )
            {
                permission = (Permission) clazz.newInstance();
            }
            else if ( name != null && actions == null )
            {
                Constructor<?> c = clazz.getConstructor(new Class[]
                { String.class });
                permission = (Permission) c.newInstance( name );
            }
            else if ( name != null && actions != null )
            {
                Constructor<?> c = clazz.getConstructor(new Class[]
                { String.class, String.class });
                permission = (Permission) c.newInstance( name, actions );
            }
          
            return permission;    
        }
        catch ( Exception e )
        {
            throw new PolicyException( e );
        }
    }
    
    @Override
    public void expand( PolicyContext context ) throws PolicyException
    {
        if ( name != null )
        {
            name = context.evaluate( name ).trim();
        }
        
        if ( actions != null )
        {
            actions = context.evaluate( actions ).trim();
        }
        
        if ( signers != null )
        {
            signerArray = resolveCertificates( context.getKeystore(), signers );
        }
        
        setExpanded( true );
    }
    
    /**
     * validate that all permission certs are present in the class certs
     * 
     * @param permCerts
     * @param classCerts
     * @return true if the permissions match up
     */
    private static boolean validate( Certificate[] permCerts, Certificate[] classCerts )
    {
        if ( classCerts == null )
        {
            return false;
        }
        
        for ( int i = 0; i < permCerts.length; ++i )
        {
            boolean found = false;           
            for ( int j = 0; j < classCerts.length; ++j )
            {
                if ( permCerts[i].equals( classCerts[j] ) )
                {
                    found = true;
                    break;
                }
            }
            // if we didn't find the permCert in the classCerts then we don't match up
            if ( found == false )
            {
                return false;
            }
        }
        
        // we found all the permCerts in classCerts so return true
        return true;
    }
    
    private static Certificate[] resolveCertificates( KeyStore keyStore, String signers ) throws PolicyException
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
        
        return certificateSet.toArray( new Certificate[certificateSet.size()]);
    }

    public String getKlass()
    {
        return klass;
    }

    public void setKlass( String klass )
    {
        this.klass = klass;
    }

    public String getName()
    {
        return name;
    }

    public void setName( String name )
    {
        this.name = name;
    }

    public String getActions()
    {
        return actions;
    }

    public void setActions( String actions )
    {
        this.actions = actions;
    }

    public String getSigners()
    {
        return signers;
    }

    public void setSigners( String signers )
    {
        this.signers = signers;
    }
    
    
    
}
