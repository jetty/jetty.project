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

import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.StringUtil;

/**
 * Sets proxy authentication headers for BASIC authentication challenges
 * 
 * 
 */
public class ProxyAuthorization implements Authentication
{
    private Buffer _authorization;
    
    public ProxyAuthorization(String username,String password) throws IOException
    {
        String authenticationString = "Basic " + B64Code.encode( username + ":" + password, StringUtil.__ISO_8859_1);
        _authorization= new ByteArrayBuffer(authenticationString);
    }
    
    /**
     * BASIC proxy authentication is of the form
     * 
     * encoded credentials are of the form: username:password
     * 
     * 
     */
    public void setCredentials( HttpExchange exchange ) throws IOException
    {
        exchange.setRequestHeader( HttpHeaders.PROXY_AUTHORIZATION_BUFFER, _authorization);
    }
}
