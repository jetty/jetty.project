//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
public class QuotedQualityCSV extends QuotedCSV implements Iterable<String>
{    
    private final static Double ZERO=new Double(0.0);
    private final static Double ONE=new Double(1.0);
    
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
        super.addValue(value);
        while(_quality.size()<_values.size())
            _quality.add(ONE);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void parsedValue(StringBuffer buffer)
    {
        super.parsedValue(buffer);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
    {
        if (buffer.charAt(paramName)=='q' && paramValue>paramName && buffer.charAt(paramName+1)=='=')
        {
            Double q;
            try
            {
                q=(_keepQuotes && buffer.charAt(paramValue)=='"')
                    ?new Double(buffer.substring(paramValue+1,buffer.length()-1))
                    :new Double(buffer.substring(paramValue));
            }
            catch(Exception e)
            {
                q=ZERO;
            }
            buffer.setLength(paramName-1);
            
            while(_quality.size()<_values.size())
                _quality.add(ONE);
            _quality.add(q);
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
        
        int last_element=_quality.size();
        while(last_element>0 && _quality.get(--last_element).equals(ZERO))
        {
            _quality.remove(last_element);
            _values.remove(last_element);
        }
    }
}
