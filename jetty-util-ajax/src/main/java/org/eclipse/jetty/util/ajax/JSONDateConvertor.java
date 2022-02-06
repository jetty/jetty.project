//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convert a {@link Date} to JSON.
 * If fromJSON is true in the constructor, the JSON generated will
 * be of the form {class="java.util.Date",value="1/1/1970 12:00 GMT"}
 * If fromJSON is false, then only the string value of the date is generated.
 */
public class JSONDateConvertor implements JSON.Convertor
{
    private static final Logger LOG = LoggerFactory.getLogger(JSONDateConvertor.class);

    private final AutoLock _lock = new AutoLock();
    private final boolean _fromJSON;
    private final DateCache _dateCache;
    private final SimpleDateFormat _format;

    public JSONDateConvertor()
    {
        this(false);
    }

    public JSONDateConvertor(boolean fromJSON)
    {
        this(DateCache.DEFAULT_FORMAT, TimeZone.getTimeZone("GMT"), fromJSON);
    }

    public JSONDateConvertor(String format, TimeZone zone, boolean fromJSON)
    {
        _dateCache = new DateCache(format, null, zone);
        _fromJSON = fromJSON;
        _format = new SimpleDateFormat(format);
        _format.setTimeZone(zone);
    }

    public JSONDateConvertor(String format, TimeZone zone, boolean fromJSON, Locale locale)
    {
        _dateCache = new DateCache(format, locale, zone);
        _fromJSON = fromJSON;
        _format = new SimpleDateFormat(format, new DateFormatSymbols(locale));
        _format.setTimeZone(zone);
    }

    @Override
    public Object fromJSON(Map<String, Object> map)
    {
        if (!_fromJSON)
            throw new UnsupportedOperationException();
        try
        {
            try (AutoLock l = _lock.lock())
            {
                return _format.parseObject((String)map.get("value"));
            }
        }
        catch (Exception e)
        {
            LOG.warn("Unable to parse JSON Object", e);
        }
        return null;
    }

    @Override
    public void toJSON(Object obj, Output out)
    {
        String date = _dateCache.format((Date)obj);
        if (_fromJSON)
        {
            out.addClass(obj.getClass());
            out.add("value", date);
        }
        else
        {
            out.add(date);
        }
    }
}
