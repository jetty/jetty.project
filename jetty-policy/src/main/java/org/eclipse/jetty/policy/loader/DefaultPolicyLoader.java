package org.eclipse.jetty.policy.loader;

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
//Portions of this file adapted for use from Apache Harmony code by written
//and contributed to that project by Alexey V. Varlamov under the ASL
//========================================================================

import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.policy.PolicyBlock;
import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;
import org.eclipse.jetty.policy.entry.GrantEntry;
import org.eclipse.jetty.policy.entry.KeystoreEntry;

/**
 * Load the policies within the stream and resolve into protection domains and permission collections 
 * 
 */
public class DefaultPolicyLoader
{
    
    public static Set<PolicyBlock> load( InputStream policyStream, PolicyContext context ) throws PolicyException
    {
        Set<PolicyBlock> policies = new HashSet<PolicyBlock>();
        KeyStore keystore = null;
        
        try
        {
            PolicyFileScanner loader = new PolicyFileScanner();
            
            Collection<GrantEntry> grantEntries = new ArrayList<GrantEntry>();
            List<KeystoreEntry> keystoreEntries = new ArrayList<KeystoreEntry>();
            
            loader.scanStream( new InputStreamReader(policyStream), grantEntries, keystoreEntries );
            
            for ( Iterator<KeystoreEntry> i = keystoreEntries.iterator(); i.hasNext();)
            {
                KeystoreEntry node = i.next();
                node.expand( context );
                
                keystore = node.toKeyStore();
                
                if ( keystore != null )
                {
                    // we only process the first valid keystore
                    context.setKeystore( keystore );
                    break;
                }
            }
            
            for ( Iterator<GrantEntry> i = grantEntries.iterator(); i.hasNext(); )
            {            
                GrantEntry grant = i.next();
                grant.expand( context );
                
                PolicyBlock policy = new PolicyBlock();             
                
                policy.setCodeSource( grant.getCodeSource() );
                policy.setPrincipals( grant.getPrincipals() );
                policy.setPermissions( grant.getPermissions() );
                
                policies.add(policy);
            }      
            
            return policies;
        }
        catch ( Exception e )
        {
            throw new PolicyException( e );
        }
    }
}





