package org.eclipse.jetty.policy.loader;

//========================================================================
//Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at 
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses. 
//
//Portions of this file adapted for use from Apache Harmony code by written
//and contributed to that project by Alexey V. Varlamov under the ASL
//========================================================================

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.security.CodeSource;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Principal;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.jetty.policy.PolicyException;
import org.eclipse.jetty.policy.PropertyEvaluator;

/**
 * Load the policies within the stream and resolve into protection domains and permission collections 
 * 
 */
public class DefaultPolicyLoader
{
    
    public static Map<ProtectionDomain, PermissionCollection> load( InputStream policyStream, PropertyEvaluator evaluator ) throws PolicyException
    {
        Map<ProtectionDomain, PermissionCollection> pdMappings = new HashMap<ProtectionDomain, PermissionCollection>();
        KeyStore keystore = null;
        
        try
        {
            PolicyFileScanner loader = new PolicyFileScanner();
            
            Collection<GrantEntry> grantEntries = new ArrayList<GrantEntry>();
            List<KeystoreEntry> keystoreEntries = new ArrayList<KeystoreEntry>();
            
            loader.scanStream( new InputStreamReader(policyStream), grantEntries, keystoreEntries );
            
            for ( Iterator<KeystoreEntry> i = keystoreEntries.iterator(); i.hasNext();)
            {
                keystore = resolveKeyStore( i.next(), evaluator );
                
                if ( keystore != null )
                {
                    // we only process the first valid keystore
                    break;
                }
            }
            
            for ( Iterator<GrantEntry> i = grantEntries.iterator(); i.hasNext(); )
            {
                GrantEntry grant = i.next();
                
                Permissions permissions = processPermissions( grant, keystore, evaluator );
                
                ProtectionDomain pd;
                
                if ( grant.codebase == null ) // these are hereby known as global permissions (no codebase associated)
                {
                    pd = new ProtectionDomain( null, permissions );
                }
                else
                {
                    Certificate[] certs = resolveToCertificates( keystore, grant.signers );
                    Principal[] principals = resolvePrincipals( keystore, grant.principals );
                    CodeSource codeSource = resolveToCodeSource( grant.codebase, certs, evaluator );    
                    pd = new ProtectionDomain( codeSource, permissions, Thread.currentThread().getContextClassLoader(),principals );
                    //System.out.println( pd.toString() );
                }                                
                pdMappings.put( pd, null );                                        
            }      
            
            return pdMappings;
        }
        catch ( Exception e )
        {
            throw new PolicyException( e );
        }
    }
    
    private static Permissions processPermissions( GrantEntry grant, KeyStore keystore, PropertyEvaluator evaluator ) throws PolicyException
    {
        Collection<PermissionEntry> collection = grant.permissions;
        Permissions permissions = new Permissions();
        
        for ( Iterator<PermissionEntry> i = collection.iterator(); i.hasNext(); )
        {
            PermissionEntry perm = i.next();           
            
            Class clazz;
            try 
            {
                clazz = Class.forName( perm.klass );
                
                // if we have perm.signers then we need to validate against the class certificates
                if ( perm.signers != null )
                {
                    if ( validate( resolveToCertificates( keystore, perm.signers ), (Certificate[]) clazz.getSigners() ))
                    {
                       permissions.add( resolveToPermission( clazz, perm, evaluator ) );
                    }                   
                }
                else
                {
                    permissions.add( resolveToPermission( clazz, perm, evaluator ) );
                }
            } 
            catch ( Exception e ) 
            {
                throw new PolicyException( e );
            }
        }
        
        return permissions;
    }
    
    private static Permission resolveToPermission(Class clazz, PermissionEntry perm, PropertyEvaluator evaluator ) throws PolicyException
    {
        try
        {
            Permission permission = null;

            if ( perm.name == null && perm.actions == null )
            {
                permission = (Permission) clazz.newInstance();
            }
            else if ( perm.name != null && perm.actions == null )
            {
                Constructor c = clazz.getConstructor( new Class[] { String.class } );
                permission = (Permission) c.newInstance( evaluator.evaluate( perm.name ) );
            }
            else if ( perm.name != null && perm.actions != null )
            {
                Constructor c = clazz.getConstructor( new Class[] { String.class, String.class } );
                permission = (Permission) c.newInstance( evaluator.evaluate( perm.name ), perm.actions );
            }

            return permission;

        }
        catch ( Exception e )
        {
            throw new PolicyException( e );
        }
    }
    
    /*
     * resolve the use of the klass in the principal entry
     * 
     * @param keystore
     * @param collection
     * @return
     * @throws PolicyException
     */
    private static Principal[] resolvePrincipals( KeyStore keystore, Collection<PrincipalEntry> collection ) throws PolicyException
    {
        if ( keystore == null || collection == null )
        {
            Principal[] principals = null;
            return principals;
        }
             
        Set<Principal> principalSet = new HashSet<Principal>();       
        
        
        for ( Iterator<PrincipalEntry> i = collection.iterator(); i.hasNext(); )
        {
            PrincipalEntry principal = i.next();
            
            try
            {               
                Certificate certificate = keystore.getCertificate( principal.name );
                
                if ( certificate instanceof X509Certificate )
                {
                    principalSet.add( ((X509Certificate) certificate).getSubjectX500Principal() );
                }              
            }
            catch ( KeyStoreException kse )
            {
                throw new PolicyException( kse );
            }
        }
        
        return principalSet.toArray( new Principal[principalSet.size()]);
    }
    
  
    private static CodeSource resolveToCodeSource( String codeBase, Certificate[] signers, PropertyEvaluator evaluator ) throws PolicyException
    {
        try
        {   
            URL url = new URL( evaluator.evaluate(codeBase) ); 
            
            return new CodeSource( url, signers);
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
    private static Certificate[] resolveToCertificates( KeyStore keyStore, String signers ) throws PolicyException
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
    
    private static KeyStore resolveKeyStore( KeystoreEntry entry, PropertyEvaluator evaluator ) throws PolicyException
    {
        KeyStore keyStore = null;
        
        try 
        {           
            keyStore = KeyStore.getInstance( entry.type );
            
            URL keyStoreLocation = new URL ( evaluator.evaluate( entry.url ) );
            InputStream istream = keyStoreLocation.openStream();
            
            keyStore.load( istream, null );
        }
        catch ( Exception e )
        {
            e.printStackTrace();
            //throw new PolicyException( kse );
        }
        
        return keyStore;
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
    
    /**
     * Compound token representing <i>keystore</i> clause. See policy format
     * {@link org.apache.harmony.security.DefaultPolicy description}for details.
     * 
     * @see org.apache.harmony.security.fortress.DefaultPolicyParser
     * @see org.apache.harmony.security.DefaultPolicyScanner
     */
    public static class KeystoreEntry
    {

        /**
         * The URL part of keystore clause.
         */
        public String url;

        /**
         * The typename part of keystore clause.
         */
        public String type;
    }

    /**
     * Compound token representing <i>grant </i> clause. See policy format
     * {@link org.apache.harmony.security.DefaultPolicy description}for details.
     * 
     * @see org.apache.harmony.security.fortress.DefaultPolicyParser
     * @see org.apache.harmony.security.DefaultPolicyScanner
     */
    public static class GrantEntry
    {

        /**
         * The signers part of grant clause. This is a comma-separated list of certificate aliases.
         */
        public String signers;

        /**
         * The codebase part of grant clause. This is an URL from which code originates.
         */
        public String codebase;

        /**
         * Collection of PrincipalEntries of grant clause.
         */
        public Collection<PrincipalEntry> principals;

        /**
         * Collection of PermissionEntries of grant clause.
         */
        public Collection<PermissionEntry> permissions;

        /**
         * Adds specified element to the <code>principals</code> collection. If collection does not exist yet, creates a
         * new one.
         */
        public void addPrincipal( PrincipalEntry pe )
        {
            if ( principals == null )
            {
                principals = new HashSet<PrincipalEntry>();
            }
            principals.add( pe );
        }

    }

    /**
     * Compound token representing <i>principal </i> entry of a <i>grant </i> clause. See policy format
     * {@link org.apache.harmony.security.DefaultPolicy description}for details.
     * 
     * @see org.apache.harmony.security.fortress.DefaultPolicyParser
     * @see org.apache.harmony.security.DefaultPolicyScanner
     */
    public static class PrincipalEntry
    {

        /**
         * Wildcard value denotes any class and/or any name. Must be asterisk, for proper general expansion and
         * PrivateCredentialsPermission wildcarding
         */
        public static final String WILDCARD = "*"; //$NON-NLS-1$

        /**
         * The classname part of principal clause.
         */
        public String klass;

        /**
         * The name part of principal clause.
         */
        public String name;
    }

    /**
     * Compound token representing <i>permission </i> entry of a <i>grant </i> clause. See policy format
     * {@link org.apache.harmony.security.DefaultPolicy description}for details.
     * 
     * @see org.apache.harmony.security.fortress.DefaultPolicyParser
     * @see org.apache.harmony.security.DefaultPolicyScanner
     */
    public static class PermissionEntry
    {

        /**
         * The classname part of permission clause.
         */
        public String klass;

        /**
         * The name part of permission clause.
         */
        public String name;

        /**
         * The actions part of permission clause.
         */
        public String actions;

        /**
         * The signers part of permission clause. This is a comma-separated list of certificate aliases.
         */
        public String signers;
    }


    
}
