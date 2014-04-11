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
import java.security.MessageDigest;
import java.util.Map;

import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

public class DigestAuthentication implements Authentication
{
    private static final String NC = "00000001";
    Realm securityRealm;
    Map details;
    
    public DigestAuthentication(Realm realm, Map details)
    {
        this.securityRealm=realm;
        this.details=details;
    }
    

    public void setCredentials( HttpExchange exchange ) 
    throws IOException
    {        
        StringBuilder buffer = new StringBuilder().append("Digest");
        
        buffer.append(" ").append("username").append('=').append('"').append(securityRealm.getPrincipal()).append('"');
        
        buffer.append(", ").append("realm").append('=').append('"').append(String.valueOf(details.get("realm"))).append('"');
        
        buffer.append(", ").append("nonce").append('=').append('"').append(String.valueOf(details.get("nonce"))).append('"');
        
        buffer.append(", ").append("uri").append('=').append('"').append(exchange.getURI()).append('"');
        
        buffer.append(", ").append("algorithm").append('=').append(String.valueOf(details.get("algorithm")));
        
        String cnonce = newCnonce(exchange, securityRealm, details);
        
        buffer.append(", ").append("response").append('=').append('"').append(newResponse(cnonce, 
                exchange, securityRealm, details)).append('"');
        
        buffer.append(", ").append("qop").append('=').append(String.valueOf(details.get("qop")));
        

        buffer.append(", ").append("nc").append('=').append(NC);
        
        buffer.append(", ").append("cnonce").append('=').append('"').append(cnonce).append('"');
        
        exchange.setRequestHeader( HttpHeaders.AUTHORIZATION, 
                new String(buffer.toString().getBytes(StringUtil.__ISO_8859_1)));
    }
    
    protected String newResponse(String cnonce, HttpExchange exchange, Realm securityRealm, Map details)
    {        
        try{
            MessageDigest md = MessageDigest.getInstance("MD5");
            
            // calc A1 digest
            md.update(securityRealm.getPrincipal().getBytes(StringUtil.__ISO_8859_1));
            md.update((byte)':');
            md.update(String.valueOf(details.get("realm")).getBytes(StringUtil.__ISO_8859_1));
            md.update((byte)':');
            md.update(securityRealm.getCredentials().getBytes(StringUtil.__ISO_8859_1));
            byte[] ha1 = md.digest();
            // calc A2 digest
            md.reset();
            md.update(exchange.getMethod().getBytes(StringUtil.__ISO_8859_1));
            md.update((byte)':');
            md.update(exchange.getURI().getBytes(StringUtil.__ISO_8859_1));
            byte[] ha2=md.digest();
            
            md.update(TypeUtil.toString(ha1,16).getBytes(StringUtil.__ISO_8859_1));
            md.update((byte)':');
            md.update(String.valueOf(details.get("nonce")).getBytes(StringUtil.__ISO_8859_1));
            md.update((byte)':');
            md.update(NC.getBytes(StringUtil.__ISO_8859_1));
            md.update((byte)':');
            md.update(cnonce.getBytes(StringUtil.__ISO_8859_1));
            md.update((byte)':');
            md.update(String.valueOf(details.get("qop")).getBytes(StringUtil.__ISO_8859_1));
            md.update((byte)':');
            md.update(TypeUtil.toString(ha2,16).getBytes(StringUtil.__ISO_8859_1));
            byte[] digest=md.digest();
            
            // check digest
            return encode(digest);
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }        
    }
    
    protected String newCnonce(HttpExchange exchange, Realm securityRealm, Map details)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] b= md.digest(String.valueOf(System.currentTimeMillis()).getBytes(StringUtil.__ISO_8859_1));            
            return encode(b);
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    private static String encode(byte[] data)
    {
        StringBuilder buffer = new StringBuilder();
        for (int i=0; i<data.length; i++) 
        {
            buffer.append(Integer.toHexString((data[i] & 0xf0) >>> 4));
            buffer.append(Integer.toHexString(data[i] & 0x0f));
        }
        return buffer.toString();
    }

}
