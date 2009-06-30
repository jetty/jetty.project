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
import java.security.KeyStore;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;
import org.eclipse.jetty.policy.PolicyEntry;
import org.eclipse.jetty.policy.component.GrantNode;
import org.eclipse.jetty.policy.component.KeystoreNode;

/**
 * Load the policies within the stream and resolve into protection domains and permission collections 
 * 
 */
public class DefaultPolicyLoader
{
    
    public static Map<ProtectionDomain, PolicyEntry> load( InputStream policyStream, PolicyContext context ) throws PolicyException
    {
        Map<ProtectionDomain, PolicyEntry> policies = new HashMap<ProtectionDomain, PolicyEntry>();
        KeyStore keystore = null;
        
        try
        {
            PolicyFileScanner loader = new PolicyFileScanner();
            
            Collection<GrantNode> grantEntries = new ArrayList<GrantNode>();
            List<KeystoreNode> keystoreEntries = new ArrayList<KeystoreNode>();
            
            loader.scanStream( new InputStreamReader(policyStream), grantEntries, keystoreEntries );
            
            for ( Iterator<KeystoreNode> i = keystoreEntries.iterator(); i.hasNext();)
            {
                KeystoreNode node = i.next();
                node.expand( context );
                
                keystore = node.toKeyStore();
                
                if ( keystore != null )
                {
                    // we only process the first valid keystore
                    context.setKeystore( keystore );
                    break;
                }
            }
            
            for ( Iterator<GrantNode> i = grantEntries.iterator(); i.hasNext(); )
            {            
                GrantNode grant = i.next();
                grant.expand( context );
                
                PolicyEntry policy = new PolicyEntry();             
                
                policy.setCodeSource( grant.getCodeSource() );
                policy.setPrincipals( grant.getPrincipals() );
                policy.setPermissions( grant.getPermissions() );
                
                policies.put( policy.toProtectionDomain(), policy );                                        
            }      
            
            return policies;
        }
        catch ( Exception e )
        {
            throw new PolicyException( e );
        }
    }
}





