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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cookie parser
 * <p>Optimized stateful cookie parser.
 * If the added fields are identical to those last added (as strings), then the
 * cookies are not re parsed.
 * 
 */
public class CookieCache
{
    protected static final Logger LOG = LoggerFactory.getLogger(CookieCache.class);
    protected final List<String> _rawFields = new ArrayList<>();
    protected List<HttpCookie> _cookieList;
    private final CookieCutter _cookieCutter;

    public CookieCache()
    {
        this(CookieCompliance.RFC6265, null);
    }

    public CookieCache(CookieCompliance compliance, ComplianceViolation.Listener complianceListener)
    {
        _cookieCutter = new CookieCutter(compliance, complianceListener)
        {
            @Override
            protected void addCookie(String cookieName, String cookieValue, String cookieDomain, String cookiePath, int cookieVersion, String cookieComment)
            {
                _cookieList.add(new HttpCookie(cookieName, cookieValue, cookieDomain, cookiePath, -1, false, false, cookieComment, cookieVersion));
            }
        };
    }

    public List<HttpCookie> getCookies(HttpFields headers)
    {
        boolean building = false;
        ListIterator<String> raw = _rawFields.listIterator();
        // For each of the headers
        for (HttpField field : headers)
        {
            // skip non cookie headers
            if (!HttpHeader.COOKIE.equals(field.getHeader()))
                continue;

            // skip blank cookie headers
            String value = field.getValue();
            if (StringUtil.isBlank(value))
                continue;

            // If we are building a new cookie list
            if (building)
            {
                // just add the raw string to the list to be parsed later
                _rawFields.add(value);
                continue;
            }

            // otherwise we are checking against previous cookies.

            // Is there a previous raw cookie to compare with?
            if (!raw.hasNext())
            {
                // No, so we will flip to building state and add to the raw fields we already have.
                building = true;
                _rawFields.add(value);
                continue;
            }

            // If there is a previous raw cookie and it is the same, then continue checking
            if (value.equals(raw.next()))
                continue;

            // otherwise there is a difference in the previous raw cookie field
            // so switch to building mode and remove all subsequent raw fields
            // then add the current raw field to be built later.
            building = true;
            raw.remove();
            while (raw.hasNext())
            {
                raw.next();
                raw.remove();
            }
            _rawFields.add(value);
        }

        // If we are not building, but there are still more unmatched raw fields, then a field was deleted
        if (!building && raw.hasNext())
        {
            // switch to building mode and delete the unmatched raw fields
            building = true;
            while (raw.hasNext())
            {
                raw.next();
                raw.remove();
            }
        }

        // If we ended up in building mode, reparse the cookie list from the raw fields.
        if (building)
        {
            _cookieList = new ArrayList<>();
            _cookieCutter.parseFields(_rawFields);
        }

        return _cookieList == null ? Collections.emptyList() : _cookieList;
    }

}
