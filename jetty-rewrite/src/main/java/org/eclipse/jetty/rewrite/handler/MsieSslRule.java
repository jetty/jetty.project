//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.Trie;

/**
 * MSIE (Microsoft Internet Explorer) SSL Rule.
 * Disable keep alive for SSL from IE5 or IE6 on Windows 2000.
 *  
 * 
 *
 */
public class MsieSslRule extends Rule
{
    private static final int IEv5 = '5';
    private static final int IEv6 = '6';
    private static Trie<Boolean> __IE6_BadOS = new ArrayTernaryTrie<>();
    {
        __IE6_BadOS.put("NT 5.01", Boolean.TRUE);
        __IE6_BadOS.put("NT 5.0",Boolean.TRUE);
        __IE6_BadOS.put("NT 4.0",Boolean.TRUE);
        __IE6_BadOS.put("98",Boolean.TRUE);
        __IE6_BadOS.put("98; Win 9x 4.90",Boolean.TRUE);
        __IE6_BadOS.put("95",Boolean.TRUE);
        __IE6_BadOS.put("CE",Boolean.TRUE);
    }
    
    public MsieSslRule()
    {
        _handling = false;
        _terminating = false;
    }
    
    @Override
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        if (request.isSecure())
        {
            String user_agent = request.getHeader(HttpHeader.USER_AGENT.asString());
            
            if (user_agent!=null)
            {
                int msie=user_agent.indexOf("MSIE");
                if (msie>0 && user_agent.length()-msie>5)
                {
                    // Get Internet Explorer Version
                    int ieVersion = user_agent.charAt(msie+5);
                    
                    if ( ieVersion<=IEv5)
                    {
                        response.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
                        return target;
                    }

                    if (ieVersion==IEv6)
                    {
                        int windows = user_agent.indexOf("Windows",msie+5);
                        if (windows>0)
                        {
                            int end=user_agent.indexOf(')',windows+8);
                            if(end<0 || __IE6_BadOS.get(user_agent,windows+8,end-windows-8)!=null)
                            {
                                response.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
                                return target;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
