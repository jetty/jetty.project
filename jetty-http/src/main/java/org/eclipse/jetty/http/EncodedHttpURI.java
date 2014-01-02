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

package org.eclipse.jetty.http;

import java.io.UnsupportedEncodingException;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.Utf8StringBuffer;

public class EncodedHttpURI extends HttpURI
{
    private final String _encoding;
    
    public EncodedHttpURI(String encoding)
    {
        super();
        _encoding = encoding;
    }
    
    
    @Override
    public String getScheme()
    {
        if (_scheme==_authority)
            return null;
        int l=_authority-_scheme;
        if (l==5 && 
            _raw[_scheme]=='h' && 
            _raw[_scheme+1]=='t' && 
            _raw[_scheme+2]=='t' && 
            _raw[_scheme+3]=='p' )
            return HttpSchemes.HTTP;
        if (l==6 && 
            _raw[_scheme]=='h' && 
            _raw[_scheme+1]=='t' && 
            _raw[_scheme+2]=='t' && 
            _raw[_scheme+3]=='p' && 
            _raw[_scheme+4]=='s' )
            return HttpSchemes.HTTPS;
        
        return StringUtil.toString(_raw,_scheme,_authority-_scheme-1,_encoding);
    }
    
    @Override
    public String getAuthority()
    {
        if (_authority==_path)
            return null;
        return StringUtil.toString(_raw,_authority,_path-_authority,_encoding);
    }
    
    @Override
    public String getHost()
    {
        if (_host==_port)
            return null;
        return StringUtil.toString(_raw,_host,_port-_host,_encoding);
    }
    
    @Override
    public int getPort()
    {
        if (_port==_path)
            return -1;
        return TypeUtil.parseInt(_raw, _port+1, _path-_port-1,10);
    }
    
    @Override
    public String getPath()
    {
        if (_path==_param)
            return null;
        return StringUtil.toString(_raw,_path,_param-_path,_encoding);
    }
    
    @Override
    public String getDecodedPath()
    {
        if (_path==_param)
            return null;
        return URIUtil.decodePath(_raw,_path,_param-_path);
    }
    
    @Override
    public String getPathAndParam()
    {
        if (_path==_query)
            return null;
        return StringUtil.toString(_raw,_path,_query-_path,_encoding);
    }
    
    @Override
    public String getCompletePath()
    {
        if (_path==_end)
            return null;
        return StringUtil.toString(_raw,_path,_end-_path,_encoding);
    }
    
    @Override
    public String getParam()
    {
        if (_param==_query)
            return null;
        return StringUtil.toString(_raw,_param+1,_query-_param-1,_encoding);
    }
    
    @Override
    public String getQuery()
    {
        if (_query==_fragment)
            return null;
        return StringUtil.toString(_raw,_query+1,_fragment-_query-1,_encoding);
    }
    
    @Override
    public boolean hasQuery()
    {
        return (_fragment>_query);
    }
    
    @Override
    public String getFragment()
    {
        if (_fragment==_end)
            return null;
        return StringUtil.toString(_raw,_fragment+1,_end-_fragment-1,_encoding);
    }

    @Override
    public void decodeQueryTo(MultiMap parameters) 
    {
        if (_query==_fragment)
            return;
        UrlEncoded.decodeTo(StringUtil.toString(_raw,_query+1,_fragment-_query-1,_encoding),parameters,_encoding);
    }

    @Override
    public void decodeQueryTo(MultiMap parameters, String encoding) 
        throws UnsupportedEncodingException
    {
        if (_query==_fragment)
            return;
       
        if (encoding==null)
            encoding=_encoding;
        UrlEncoded.decodeTo(StringUtil.toString(_raw,_query+1,_fragment-_query-1,encoding),parameters,encoding);
    }
    
    @Override
    public String toString()
    {
        if (_rawString==null)
            _rawString= StringUtil.toString(_raw,_scheme,_end-_scheme,_encoding);
        return _rawString;
    }
    
    public void writeTo(Utf8StringBuffer buf)
    {
        buf.getStringBuffer().append(toString());
    }
    
}
