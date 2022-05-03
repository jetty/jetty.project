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
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cookie parser
 * <p>Optimized stateful cookie parser.  Cookies fields are added with the
 * {@link #addCookieField(String)} method and parsed on the next subsequent
 * call to {@link #getCookies(HttpFields)}.
 * If the added fields are identical to those last added (as strings), then the
 * cookies are not re parsed.
 * 
 */
public class CookieCache extends CookieCutter
{
    protected static final Logger LOG = LoggerFactory.getLogger(CookieCache.class);
    protected final List<String> _rawFields = new ArrayList<>();
    protected final List<HttpCookie> _cookieList = new ArrayList<>();
    private int _addedFields;
    private boolean _parsed = false;
    private boolean _set = false;

    public CookieCache()
    {
        this(CookieCompliance.RFC6265, null);
    }

    public CookieCache(CookieCompliance compliance, ComplianceViolation.Listener complianceListener)
    {
        super(compliance, complianceListener);
    }

    private void addCookieField(String rawField)
    {
        if (_set)
            throw new IllegalStateException();

        if (rawField == null)
            return;
        rawField = rawField.trim();
        if (rawField.length() == 0)
            return;

        if (_rawFields.size() > _addedFields)
        {
            if (rawField.equals(_rawFields.get(_addedFields)))
            {
                _addedFields++;
                return;
            }

            while (_rawFields.size() > _addedFields)
            {
                _rawFields.remove(_addedFields);
            }
        }
        _rawFields.add(_addedFields++, rawField);
        _parsed = false;
    }

    public List<HttpCookie> getCookies(HttpFields headers)
    {
        // TODO this could be done a lot better with a single iteration and not creating a new list etc.
        _set = false;
        _addedFields = 0;
        for (HttpField field : headers)
        {
            if (HttpHeader.COOKIE.equals(field.getHeader()))
                addCookieField(field.getValue());
        }

        while (_rawFields.size() > _addedFields)
        {
            _rawFields.remove(_addedFields);
            _parsed = false;
        }

        if (_parsed)
            return _cookieList;

        parseFields(_rawFields);
        _parsed = true;
        return _cookieList;
    }

    @Override
    protected void addCookie(String name, String value, String domain, String path, int version, String comment)
    {
        try
        {
            // TODO probably should only do name & value now.  Version is not longer a thing!
            HttpCookie cookie = new HttpCookie(name, value, domain, path, -1, false, false, comment, version);
            _cookieList.add(cookie);
        }
        catch (Exception e)
        {
            LOG.debug("Unable to add Cookie name={}, value={}, domain={}, path={}, version={}, comment={}",
                name, value, domain, path, version, comment, e);
        }
    }
}
