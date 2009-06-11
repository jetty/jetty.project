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
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.policy.PolicyException;
import org.eclipse.jetty.policy.PropertyEvaluator;

/**
 * Load the policies within the stream and resolve into protection domains and permission collections 
 * 
 * TODO: currently this loading does not support keystores or certificates
 */
public class DefaultPolicyLoader
{

    public static Map<ProtectionDomain, PermissionCollection> load( InputStream policyStream, PropertyEvaluator evaluator ) throws PolicyException
    {
        Map<ProtectionDomain, PermissionCollection> pdMappings = new HashMap<ProtectionDomain, PermissionCollection>();
        
        try
        {
            PolicyFileScanner loader = new PolicyFileScanner();
            
            Collection<GrantEntry> grantEntries = new ArrayList<GrantEntry>();
            List<KeystoreEntry> keystoreEntries = new ArrayList<KeystoreEntry>();
            
            loader.scanStream( new InputStreamReader(policyStream), grantEntries, keystoreEntries );
            
            for ( Iterator<GrantEntry> i = grantEntries.iterator(); i.hasNext(); )
            {
                GrantEntry grant = i.next();
                
                Permissions permissions = processPermissions( grant.permissions, evaluator );
                
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
    
    private static Permissions processPermissions( Collection<PermissionEntry> collection, PropertyEvaluator evaluator ) throws PolicyException
    {
        Permissions permissions = new Permissions();
        
        for ( Iterator<PermissionEntry> i = collection.iterator(); i.hasNext(); )
        {
            PermissionEntry perm = i.next();
            
            Class clazz;
            try 
            {
                clazz = Class.forName( perm.klass );
                
                if ( perm.name == null && perm.actions == null )
                {                  
                    permissions.add( (Permission)clazz.newInstance() );
                }
                else if ( perm.name != null && perm.actions == null )
                {
                    Constructor c = clazz.getConstructor(new Class[] { String.class });
                    permissions.add( (Permission)c.newInstance( evaluator.evaluate( perm.name ) ) );
                }
                else if ( perm.name != null && perm.actions != null )
                {
                    Constructor c = clazz.getConstructor(new Class[] { String.class, String.class });
                    permissions.add( (Permission)c.newInstance( evaluator.evaluate( perm.name ), perm.actions ) );
                }
                
            } 
            catch ( Exception e ) 
            {
                throw new PolicyException( e );
            }
        }
        
        return permissions;
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
     * Compound token representing <i>keystore </i> clause. See policy format
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
