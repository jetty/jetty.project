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
                _cookieList.add(new HttpCookie(cookieName, cookieValue));
            }
        };
    }

    public List<HttpCookie> getCookies(HttpFields headers)
    {
        boolean building = false;
        ListIterator<String> raw = _rawFields.listIterator();
        for (HttpField field : headers)
        {
            if (!HttpHeader.COOKIE.equals(field.getHeader()))
                continue;

            String value = field.getValue();
            if (StringUtil.isBlank(value))
                continue;

            if (building)
            {
                _rawFields.add(value);
                continue;
            }

            if (!raw.hasNext())
            {
                building = true;
                _rawFields.add(value);
                continue;
            }

            if (value.equals(raw.next()))
                continue;

            // non match, so build a new cookie list
            building = true;
            raw.remove();
            while (raw.hasNext())
            {
                raw.next();
                raw.remove();
            }
            _rawFields.add(value);
        }

        if (!building && raw.hasNext())
        {
            building = true;
            while (raw.hasNext())
            {
                raw.next();
                raw.remove();
            }
        }

        if (building)
        {
            _cookieList = new ArrayList<>();
            _cookieCutter.parseFields(_rawFields);
        }

        return _cookieList == null ? Collections.emptyList() : _cookieList;
    }

}
