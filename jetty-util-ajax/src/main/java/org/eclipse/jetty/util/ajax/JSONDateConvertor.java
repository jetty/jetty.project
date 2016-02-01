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

package org.eclipse.jetty.util.ajax;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.ajax.JSON.Output;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
* Convert a {@link Date} to JSON.
* If fromJSON is true in the constructor, the JSON generated will
* be of the form {class="java.util.Date",value="1/1/1970 12:00 GMT"}
* If fromJSON is false, then only the string value of the date is generated.
*/
public class JSONDateConvertor implements JSON.Convertor
{
    private static final Logger LOG = Log.getLogger(JSONDateConvertor.class);

    private final boolean _fromJSON;
    private final DateCache _dateCache;
    private final SimpleDateFormat _format;

    public JSONDateConvertor()
    {
        this(false);
    }

    public JSONDateConvertor(boolean fromJSON)
    {
        this(DateCache.DEFAULT_FORMAT,TimeZone.getTimeZone("GMT"),fromJSON);
    }

    public JSONDateConvertor(String format,TimeZone zone,boolean fromJSON)
    {
        _dateCache=new DateCache(format,null,zone);
        _fromJSON=fromJSON;
        _format=new SimpleDateFormat(format);
        _format.setTimeZone(zone);
    }

    public JSONDateConvertor(String format, TimeZone zone, boolean fromJSON, Locale locale)
    {
        _dateCache = new DateCache(format, locale, zone);
        _fromJSON = fromJSON;
        _format = new SimpleDateFormat(format, new DateFormatSymbols(locale));
        _format.setTimeZone(zone);
    }

    public Object fromJSON(Map map)
    {
        if (!_fromJSON)
            throw new UnsupportedOperationException();
        try
        {
            synchronized(_format)
            {
                return _format.parseObject((String)map.get("value"));
            }
        }
        catch(Exception e)
        {
            LOG.warn(e);
        }
        return null;
    }

    public void toJSON(Object obj, Output out)
    {
        String date = _dateCache.format((Date)obj);
        if (_fromJSON)
        {
            out.addClass(obj.getClass());
            out.add("value",date);
        }
        else
        {
            out.add(date);
        }
    }
}
