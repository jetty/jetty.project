//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.security;

import java.io.IOException;

import org.eclipse.jetty.client.HttpDestination;

/**
 * Simple Realm Resolver.
 * <p> A Realm Resolver that wraps a single realm.
 * 
 *
 */
public class SimpleRealmResolver implements RealmResolver
{
    private Realm _realm;
    
    public SimpleRealmResolver( Realm realm )
    {
        _realm=realm;
    }
    
    public Realm getRealm( String realmName, HttpDestination destination, String path ) throws IOException
    {
        return _realm;
    }
}
