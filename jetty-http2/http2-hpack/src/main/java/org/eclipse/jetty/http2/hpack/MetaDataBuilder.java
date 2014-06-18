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


import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;

/* -------------------------------------------------------- */
/* -------------------------------------------------------- */
/* -------------------------------------------------------- */
public class MetaDataBuilder
{ 
    private int _status;
    private String _method;
    private HttpScheme _scheme;
    private HostPortHttpField _authority;
    private String _path;        

    HttpFields _fields = new HttpFields(10);
    
    public void emit(HttpField field)
    {        
        if (field instanceof StaticValueHttpField)
        {
            StaticValueHttpField value = (StaticValueHttpField)field;
            switch(field.getName())
            {
                case ":status":
                    _status=(Integer)value.getStaticValue();
                    break;
                    
                case ":method":
                    _method=field.getValue();
                    break;

                case ":scheme":
                    _scheme = (HttpScheme)value.getStaticValue();
                    break;
                    
                default:
                    throw new IllegalArgumentException();
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
                    _method=field.getValue();
                    break;

                case ":scheme":
                    _scheme = HttpScheme.CACHE.get(field.getValue());
                    break;

                case ":authority":
                    _authority=(field instanceof HostPortHttpField)?((HostPortHttpField)field):new AuthorityHttpField(field.getValue());
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
            HttpFields fields = _fields;
            _fields = new HttpFields(Math.max(10,fields.size()+5));
            if (_method!=null)
                return new MetaData.Request(HttpVersion.HTTP_2,_scheme,_method,_authority,_path,fields);
            if (_status!=0)
                return new MetaData.Response(HttpVersion.HTTP_2,_status,fields);
            return new MetaData(HttpVersion.HTTP_2,fields);
        }
        finally
        {
            _status=0;
            _method=null;
            _scheme=null;
            _authority=null;
            _path=null;
        }
    }
}
