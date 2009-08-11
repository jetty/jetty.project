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

import java.io.InputStream;
import java.net.URL;
import java.security.KeyStore;

import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;

public class KeystoreEntry extends AbstractEntry
{
    /**
     * The URL part of keystore clause.
     */
    private String url;

    /**
     * The typename part of keystore clause.
     */
    private String type;
    
    // cached value
    private KeyStore keystore;

    public KeyStore toKeyStore() throws PolicyException
    { 
        if ( keystore != null && !isDirty() )
        {
            return keystore;
        }
        
        try 
        {           
            keystore = KeyStore.getInstance( type );
            
            URL keyStoreLocation = new URL ( url );
            InputStream istream = keyStoreLocation.openStream();
            
            keystore.load( istream, null );
        }
        catch ( Exception e )
        {
            throw new PolicyException( e );
        }
        
        return keystore; 
    }
    
    @Override
    public void expand( PolicyContext context ) throws PolicyException
    {
        url = context.evaluate( url );
        
        setExpanded( true );
    }

    public String getUrl()
    {
        return url;
    }

    public void setUrl( String url )
    {
        this.url = url;
    }

    public String getType()
    {
        return type;
    }

    public void setType( String type )
    {
        this.type = type;
    }    
}
