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

package org.eclipse.jetty.http;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * ThreadLocal data parsers for HTTP style dates
 */
public class DateParser
{
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    static
    {
        GMT.setID("GMT");
    }

    static final String[] DATE_RECEIVE_FMT =
    {
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd-MMM-yy HH:mm:ss",
        "EEE MMM dd HH:mm:ss yyyy",

        "EEE, dd MMM yyyy HH:mm:ss", "EEE dd MMM yyyy HH:mm:ss zzz",
        "EEE dd MMM yyyy HH:mm:ss", "EEE MMM dd yyyy HH:mm:ss zzz", "EEE MMM dd yyyy HH:mm:ss",
        "EEE MMM-dd-yyyy HH:mm:ss zzz", "EEE MMM-dd-yyyy HH:mm:ss", "dd MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss", "dd-MMM-yy HH:mm:ss zzz", "dd-MMM-yy HH:mm:ss", "MMM dd HH:mm:ss yyyy zzz",
        "MMM dd HH:mm:ss yyyy", "EEE MMM dd HH:mm:ss yyyy zzz",
        "EEE, MMM dd HH:mm:ss yyyy zzz", "EEE, MMM dd HH:mm:ss yyyy", "EEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE dd-MMM-yy HH:mm:ss zzz", "EEE dd-MMM-yy HH:mm:ss"
    };

    public static long parseDate(String date)
    {
        return DATE_PARSER.get().parse(date);
    }

    private static final ThreadLocal<DateParser> DATE_PARSER = new ThreadLocal<DateParser>()
    {
        @Override
        protected DateParser initialValue()
        {
            return new DateParser();
        }
    };

    final SimpleDateFormat[] _dateReceive = new SimpleDateFormat[DATE_RECEIVE_FMT.length];

    private long parse(final String dateVal)
    {
        for (int i = 0; i < _dateReceive.length; i++)
        {
            if (_dateReceive[i] == null)
            {
                _dateReceive[i] = new SimpleDateFormat(DATE_RECEIVE_FMT[i], Locale.US);
                _dateReceive[i].setTimeZone(GMT);
            }

            try
            {
                Date date = (Date)_dateReceive[i].parseObject(dateVal);
                return date.getTime();
            }
            catch (java.lang.Exception ignored)
            {
            }
        }

        if (dateVal.endsWith(" GMT"))
        {
            final String val = dateVal.substring(0, dateVal.length() - 4);

            for (SimpleDateFormat element : _dateReceive)
            {
                try
                {
                    Date date = (Date)element.parseObject(val);
                    return date.getTime();
                }
                catch (java.lang.Exception ignored)
                {
                }
            }
        }
        return -1;
    }
}
