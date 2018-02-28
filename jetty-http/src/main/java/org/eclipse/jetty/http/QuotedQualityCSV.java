//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static java.lang.Integer.MIN_VALUE;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

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
    

    /**
     * Function to apply a most specific MIME encoding secondary ordering 
     */
    public static Function<String, Integer> MOST_SPECIFIC = new Function<String, Integer>()
    {
        @Override
        public Integer apply(String s)
        {
            String[] elements = s.split("/");
            return 1000000*elements.length+1000*elements[0].length()+elements[elements.length-1].length();
        }
    };
    
    private final List<Double> _quality = new ArrayList<>();
    private boolean _sorted = false;
    private final Function<String, Integer> _secondaryOrdering;
    
    /* ------------------------------------------------------------ */
    /**
     * Sorts values with equal quality according to the length of the value String.
     */
    public QuotedQualityCSV()
    {
        this((s) -> 0);
    }

    /* ------------------------------------------------------------ */
    /**
     * Sorts values with equal quality according to given order.
     * @param preferredOrder Array indicating the preferred order of known values
     */
    public QuotedQualityCSV(String[] preferredOrder)
    {
        this((s) -> {
            for (int i=0;i<preferredOrder.length;++i)
                if (preferredOrder[i].equals(s))
                    return preferredOrder.length-i;

            if ("*".equals(s))
                return preferredOrder.length;

            return MIN_VALUE;
        });
    }

    /* ------------------------------------------------------------ */
    /**
     * Orders values with equal quality with the given function.
     * @param secondaryOrdering Function to apply an ordering other than specified by quality
     */
    public QuotedQualityCSV(Function<String, Integer> secondaryOrdering)
    {
        this._secondaryOrdering = secondaryOrdering;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void parsedValue(StringBuffer buffer)
    {
        super.parsedValue(buffer);
        _quality.add(ONE);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
    {
        if (paramName<0)
        {
            if (buffer.charAt(buffer.length()-1)==';')
                buffer.setLength(buffer.length()-1);
        }
        else if (paramValue>=0 && 
            buffer.charAt(paramName)=='q' && paramValue>paramName && 
            buffer.length()>=paramName && buffer.charAt(paramName+1)=='=')
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
            buffer.setLength(Math.max(0,paramName-1));
            
           if (!ONE.equals(q))
               _quality.set(_quality.size()-1,q);
        }
    }

    @Override
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
        int lastSecondaryOrder = Integer.MIN_VALUE;

        for (int i = _values.size(); i-- > 0;)
        {
            String v = _values.get(i);
            Double q = _quality.get(i);

            int compare=last.compareTo(q);
            if (compare>0 || (compare==0 && _secondaryOrdering.apply(v)<lastSecondaryOrder))
            {
                _values.set(i, _values.get(i + 1));
                _values.set(i + 1, v);
                _quality.set(i, _quality.get(i + 1));
                _quality.set(i + 1, q);
                last = ZERO;
                lastSecondaryOrder=0;
                i = _values.size();
                continue;
            }

            last=q;
            lastSecondaryOrder=_secondaryOrdering.apply(v);
        }
        
        int last_element=_quality.size();
        while(last_element>0 && _quality.get(--last_element).equals(ZERO))
        {
            _quality.remove(last_element);
            _values.remove(last_element);
        }
    }
}
