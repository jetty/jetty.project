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


package org.eclipse.jetty.http2.hpack;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;

/* -------------------------------------------------------- */
/* -------------------------------------------------------- */
/* -------------------------------------------------------- */
public class MetaDataBuilder
{ 
    private int _status;
    private HttpMethod _method;
    private String _methodString;
    private HttpScheme _scheme;
    private String _authority;
    private String _path;        

    List<HttpField> _fields = new ArrayList<>();
    
    public void emit(HttpField field)
    {
        if (field instanceof HpackContext.StaticValueHttpField)
        {
            HpackContext.StaticValueHttpField value = (HpackContext.StaticValueHttpField)field;
            switch(field.getName())
            {
                case ":status":
                    _status=(Integer)value.getStaticValue();
                    break;
                    
                case ":method":
                    _method=(HttpMethod)value.getStaticValue();
                    _methodString=_method.asString();
                    break;

                case ":scheme":
                    _scheme = (HttpScheme)value.getStaticValue();
                    break;
                    
                case ":authority":
                    _authority=field.getValue();
                    break;
                    
                case ":path":
                    _path=field.getValue();
                    break;
                    
                default:
                    if (field.getName().charAt(0)!=':')
                        _fields.add(field);
            }
        }
        else
        {

            switch(field.getName())
            {
                case ":status":
                    _status=Integer.parseInt(field.getValue());
                    break;

                case ":method":
                    _methodString=field.getValue();
                    _method=HttpMethod.CACHE.get(_methodString);
                    break;

                case ":scheme":
                    _scheme = HttpScheme.valueOf(field.getValue());
                    break;

                case ":authority":
                    _authority=field.getValue();
                    break;

                case ":path":
                    _path=field.getValue();
                    break;

                default:
                    if (field.getName().charAt(0)!=':')
                        _fields.add(field);
            }
        }
    }
    
    public MetaData build()
    {
        try
        {
            if (_method!=null)
                return new MetaData.Request(_scheme,_method,_methodString,_authority,_path,new ArrayList<>(_fields));
            if (_status!=0)
                return new MetaData.Response(_status,new ArrayList<>(_fields));
            return new MetaData(new ArrayList<>(_fields));
        }
        finally
        {
            _status=0;
            _method=null;
            _scheme=null;
            _authority=null;
            _path=null;
            _fields.clear();
        }
    }
}
