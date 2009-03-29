// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.client.security;

import junit.framework.TestCase;

public class SecurityResolverTest extends TestCase
{
    public void testNothing()
    {
        
    }
    /* TODO

    public void testCredentialParsing() throws Exception
    {
        SecurityListener resolver = new SecurityListener();
        Buffer value = new ByteArrayBuffer("basic a=b".getBytes());
        
        assertEquals( "basic", resolver.scrapeAuthenticationType( value.toString() ) );
        assertEquals( 1, resolver.scrapeAuthenticationDetails( value.toString() ).size() );

        value = new ByteArrayBuffer("digest a=boo, c=\"doo\" , egg=foo".getBytes());
        
        assertEquals( "digest", resolver.scrapeAuthenticationType( value.toString() ) );
        Map<String,String> testMap = resolver.scrapeAuthenticationDetails( value.toString() );
        assertEquals( 3, testMap.size() );
        assertEquals( "boo", testMap.get("a") );
        assertEquals( "doo", testMap.get("c") );
        assertEquals( "foo", testMap.get("egg") );
    }
    
    */
}
