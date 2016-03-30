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

package org.eclipse.jetty.http;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* ------------------------------------------------------------ */
/**
 * Implements a quoted comma separated list of quality values
 * in accordance with RFC7230 and RFC7231.
 * Values are returned sorted in quality order, with OWS and the 
 * quality parameters removed.
 * @see "https://tools.ietf.org/html/rfc7230#section-3.2.6"
 * @see "https://tools.ietf.org/html/rfc7230#section-7"
 * @see "https://tools.ietf.org/html/rfc7231#section-5.3.1"
 */
public class QuotedQualityCSV implements Iterable<String>
{    
    private final static Double ZERO=new Double(0.0);
    private final static Double ONE=new Double(1.0);
    private enum State { VALUE, PARAM_NAME, PARAM_VALUE, Q_VALUE};
    
    private final List<String> _values = new ArrayList<>();
    private final List<Double> _quality = new ArrayList<>();
    private boolean _sorted = false;
    
    /* ------------------------------------------------------------ */
    public QuotedQualityCSV(String... values)
    {
        for (String v:values)
            addValue(v);
    }

    
    /* ------------------------------------------------------------ */
    public void addValue(String value)
    {
        StringBuffer buffer = new StringBuffer();
        
        int l=value.length();
        State state=State.VALUE;
        boolean quoted=false;
        boolean sloshed=false;
        int nws_length=0;
        int last_length=0;
        Double q=ONE;
        for (int i=0;i<=l;i++)
        {
            char c=i==l?0:value.charAt(i);
            
            // Handle quoting https://tools.ietf.org/html/rfc7230#section-3.2.6
            if (quoted && c!=0)
            {
                if (sloshed)
                    sloshed=false;
                else
                {
                    switch(c)
                    {
                        case '\\':
                            sloshed=true;
                            break;
                        case '"':
                            quoted=false;
                            if (state==State.Q_VALUE)
                                continue;
                            break;
                    }
                }

                buffer.append(c);
                nws_length=buffer.length();
                continue;
            }
            
            // Handle common cases
            switch(c)
            {
                case ' ':
                case '\t':
                    if (buffer.length()>last_length) // not leading OWS
                        buffer.append(c);
                    continue;

                case '"':
                    quoted=true;
                    if (state==State.Q_VALUE)
                        continue;
        
                    buffer.append(c);
                    nws_length=buffer.length();
                    continue;
                    
                case ';':
                    if (state==State.Q_VALUE)
                    {
                        try
                        {
                            q=new Double(buffer.substring(last_length));
                        }
                        catch(Exception e)
                        {
                            q=ZERO;
                        }
                        nws_length=last_length;
                    }
                    
                    buffer.setLength(nws_length); // trim following OWS
                    buffer.append(c);
                    last_length=++nws_length;
                    state=State.PARAM_NAME;
                    continue;
                    
                case ',':
                case 0:
                    if (state==State.Q_VALUE)
                    {
                        try
                        {
                            q=new Double(buffer.substring(last_length));
                        }
                        catch(Exception e)
                        {
                            q=ZERO;
                        }
                        nws_length=last_length;
                    }
                    buffer.setLength(nws_length); // trim following OWS
                    if (q>0.0 && nws_length>0)
                    {
                        _values.add(buffer.toString());
                        _quality.add(q);
                        _sorted=false;
                    }
                    buffer.setLength(0);
                    last_length=0;
                    nws_length=0;
                    q=ONE;
                    state=State.VALUE;
                    continue;

                default:
                {
                    switch (state)
                    {
                        case VALUE:
                        {
                            buffer.append(c);
                            nws_length=buffer.length();
                            continue;
                        }

                        case PARAM_NAME:
                        {
                            if (c=='=')
                            {
                                buffer.setLength(nws_length); // trim following OWS
                                if (nws_length-last_length==1 && Character.toLowerCase(buffer.charAt(last_length))=='q')
                                {
                                    buffer.setLength(last_length-1);
                                    nws_length=buffer.length();
                                    last_length=nws_length;
                                    state=State.Q_VALUE;
                                    continue;
                                }
                                buffer.append(c);
                                last_length=++nws_length;
                                state=State.PARAM_VALUE;
                                continue;
                            }
                            buffer.append(c);
                            nws_length=buffer.length();
                            continue;
                        }

                        case PARAM_VALUE:
                        case Q_VALUE:
                        {
                            buffer.append(c);
                            nws_length=buffer.length();
                            continue;
                        }
                    }
                }
            }  
        }
    }

    public List<String> getValues()
    {
        if (!_sorted)
            sort();
        return _values;
    }
    
    @Override
    public Iterator<String> iterator()
    {
        if (!_sorted)
            sort();
        return _values.iterator();
    }

    protected void sort()
    {
        _sorted=true;

        Double last = ZERO;
        int len = Integer.MIN_VALUE;

        for (int i = _values.size(); i-- > 0;)
        {
            String v = _values.get(i);
            Double q = _quality.get(i);

            int compare=last.compareTo(q);
            if (compare > 0  || (compare==0 && v.length()<len))
            {
                _values.set(i, _values.get(i + 1));
                _values.set(i + 1, v);
                _quality.set(i, _quality.get(i + 1));
                _quality.set(i + 1, q);
                last = ZERO;
                len=0;
                i = _values.size();
                continue;
            }

            last=q;
            len=v.length();

        }
    }
}
