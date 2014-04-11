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
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.client.HttpDestination;

public class HashRealmResolver implements RealmResolver
{
    private Map<String, Realm>_realmMap;  
    
    public void addSecurityRealm( Realm realm )
    {
        if (_realmMap == null)
        {
            _realmMap = new HashMap<String, Realm>();
        }
        _realmMap.put( realm.getId(), realm );
    }
    
    public Realm getRealm( String realmName, HttpDestination destination, String path ) throws IOException
    {
        return _realmMap.get( realmName );
    }

}
