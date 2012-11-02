//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpCookie;

public class HttpCookieParser
{
    private static final String[] DATE_PATTERNS = new String[]
            {
                    "EEE',' dd-MMM-yyyy HH:mm:ss 'GMT'",
                    "EEE',' dd MMM yyyy HH:mm:ss 'GMT'",
                    "EEE MMM dd yyyy HH:mm:ss 'GMT'Z"
            };

    private HttpCookieParser()
    {
    }

    public static List<HttpCookie> parseCookies(String headerValue)
    {
        if (headerValue.toLowerCase(Locale.ENGLISH).contains("expires="))
        {
            HttpCookie cookie = parseCookie(headerValue, 0);
            if (cookie != null)
                return Collections.singletonList(cookie);
            else
                return Collections.emptyList();
        }
        else
        {
            List<HttpCookie> result = new ArrayList<>();
            List<String> cookieStrings = splitCookies(headerValue);
            for (String cookieString : cookieStrings)
            {
                HttpCookie cookie = parseCookie(cookieString, 1);
                if (cookie != null)
                    result.add(cookie);
            }
            return result;
        }
    }

    private static List<String> splitCookies(String headerValue)
    {
        // The comma is the separator, but only if it's outside double quotes
        List<String> result = new ArrayList<>();
        int start = 0;
        int quotes = 0;
        for (int i = 0; i < headerValue.length(); ++i)
        {
            char c = headerValue.charAt(i);
            if (c == ',' && quotes % 2 == 0)
            {
                result.add(headerValue.substring(start, i));
                start = i + 1;
            }
            else if (c == '"')
            {
                ++quotes;
            }
        }
        result.add(headerValue.substring(start));
        return result;
    }

    private static HttpCookie parseCookie(String cookieString, int version)
    {
        String[] cookieParts = cookieString.split(";");

        String nameValue = cookieParts[0];
        int equal = nameValue.indexOf('=');
        if (equal < 1)
            return null;

        String name = nameValue.substring(0, equal).trim();
        String value = nameValue.substring(equal + 1);
        String domain = null;
        String path = "/";
        long maxAge = -1;
        boolean httpOnly = false;
        boolean secure = false;
        String comment = null;
        for (int i = 1; i < cookieParts.length; ++i)
        {
            try
            {
                String[] attributeParts = cookieParts[i].split("=", 2);
                String attributeName = attributeParts[0].trim().toLowerCase(Locale.ENGLISH);
                String attributeValue = attributeParts.length < 2 ? "" : attributeParts[1].trim();
                switch (attributeName)
                {
                    case "domain":
                    {
                        domain = attributeValue;
                        break;
                    }
                    case "path":
                    {
                        path = attributeValue;
                        break;
                    }
                    case "max-age":
                    {
                        maxAge = Long.parseLong(attributeValue);
                        break;
                    }
                    case "expires":
                    {
                        maxAge = parseDate(attributeValue);
                        break;
                    }
                    case "secure":
                    {
                        secure = true;
                        break;
                    }
                    case "httponly":
                    {
                        httpOnly = true;
                    }
                    case "comment":
                    {
                        comment = attributeValue;
                        break;
                    }
                    case "version":
                    {
                        version = Integer.parseInt(attributeValue);
                        break;
                    }
                    default:
                    {
                        // Ignore
                        break;
                    }
                }
            }
            catch (NumberFormatException x)
            {
                // Ignore
            }
        }

        return new HttpCookie(name, value, domain, path, maxAge, httpOnly, secure, comment, version);
    }

    private static long parseDate(String attributeValue)
    {
        for (String pattern : DATE_PATTERNS)
        {
            try
            {
                SimpleDateFormat dateFormat = new SimpleDateFormat(pattern, Locale.US);
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
                long result = TimeUnit.MILLISECONDS.toSeconds(dateFormat.parse(attributeValue).getTime() - System.currentTimeMillis());
                if (result < 0)
                    return 0;
            }
            catch (ParseException x)
            {
                // Ignore and continue
            }
        }
        return 0;
    }
}
