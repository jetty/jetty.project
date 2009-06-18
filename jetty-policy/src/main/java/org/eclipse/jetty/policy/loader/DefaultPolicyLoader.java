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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
            }
            
            for ( Iterator<GrantEntry> i = grantEntries.iterator(); i.hasNext(); )
            {
                GrantEntry grant = i.next();
                
                Permissions permissions = processPermissions( grant.permissions, keystore, evaluator );
                
                ProtectionDomain pd;
                
                if ( grant.codebase == null ) // these are hereby known as global permissions (no codebase associated)
                {
                    pd = new ProtectionDomain( null, permissions );
                }
                else
                {
                    CodeSource codeSource = resolveToCodeSource( grant.codebase, evaluator );    
                    pd = new ProtectionDomain( codeSource, permissions );
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
    
    private static Permissions processPermissions( Collection<PermissionEntry> collection, KeyStore keystore, PropertyEvaluator evaluator ) throws PolicyException
    {
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
  
    private static CodeSource resolveToCodeSource( String codeBase, PropertyEvaluator evaluator ) throws PolicyException
    {
        try
        {   
            URL url = new URL( evaluator.evaluate(codeBase) ); 
            Certificate[] cert = null;
            return new CodeSource( url, cert); //TODO support certificates
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
        StringTokenizer strTok = new StringTokenizer( signers, ",");
        
        Certificate[] certificates = new Certificate[strTok.countTokens()];
        
        for ( int i = 0; strTok.hasMoreTokens(); ++i )
        {
            try
            {
                certificates[i] = keyStore.getCertificate( strTok.nextToken().trim() );
            }
            catch ( KeyStoreException kse )
            {
                throw new PolicyException( kse );
            }
        }
        
        return certificates;
    }
    
    private static KeyStore resolveKeyStore( KeystoreEntry entry, PropertyEvaluator evaluator ) throws PolicyException
    {
        try 
        {
            KeyStore keyStore = KeyStore.getInstance( entry.type );
            
            URL keyStoreLocation = new URL ( entry.url );
            
            InputStream istream = keyStoreLocation.openStream();
            
            keyStore.load( istream, null );
            
            return keyStore;
        }
        catch ( KeyStoreException kse )
        {
            throw new PolicyException( kse );
        }
        catch ( MalformedURLException me )
        {
            throw new PolicyException( me );
        }
        catch ( IOException ioe )
        {
            throw new PolicyException( ioe );
        }
        catch ( NoSuchAlgorithmException e )
        {
            throw new PolicyException( e );
        }
        catch ( CertificateException ce )
        {
            throw new PolicyException( ce );
        }       
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
