//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.nested;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.Cookie;
import org.eclipse.jetty.http.BadMessage;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.CookieParser;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cookie parser
 * <p>Optimized stateful cookie parser.  Cookies fields are added with the
 * {@link #addCookieField(String)} method and parsed on the next subsequent
 * call to {@link #getCookies()}.
 * If the added fields are identical to those last added (as strings), then the
 * cookies are not re parsed.
 */
public class Cookies implements CookieParser.Handler
{
    protected static final Logger LOG = LoggerFactory.getLogger(Cookies.class);
    protected final List<String> _rawFields = new ArrayList<>();
    protected final List<Cookie> _cookieList = new ArrayList<>();
    private int _addedFields;
    private boolean _parsed = false;
    private Cookie[] _cookies;
    private boolean _set = false;

    private final CookieParser _parser;

    public Cookies()
    {
        this(CookieCompliance.RFC6265, null);
    }

    public Cookies(CookieCompliance compliance, ComplianceViolation.Listener complianceListener)
    {
        _parser = CookieParser.newParser(this, compliance, complianceListener);
    }

    public void addCookieField(String rawField)
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

    public Cookie[] getCookies()
    {
        if (_set)
            return _cookies;

        while (_rawFields.size() > _addedFields)
        {
            _rawFields.remove(_addedFields);
            _parsed = false;
        }

        if (_parsed)
            return _cookies;

        try
        {
            _parser.parseFields(_rawFields);
        }
        catch (CookieParser.InvalidCookieException invalidCookieException)
        {
            throw new BadMessage.RuntimeException(HttpStatus.BAD_REQUEST_400, invalidCookieException.getMessage(), invalidCookieException);
        }
        _cookies = _cookieList.toArray(new Cookie[0]);
        _cookieList.clear();
        _parsed = true;
        return _cookies;
    }

    public void setCookies(Cookie[] cookies)
    {
        _cookies = cookies;
        _set = true;
    }

    public void reset()
    {
        if (_set)
            _cookies = null;
        _set = false;
        _addedFields = 0;
    }

    @Override
    public void addCookie(String name, String value, int version, String domain, String path, String comment)
    {
        try
        {
            Cookie cookie = new Cookie(name, value);
            if (domain != null)
                cookie.setDomain(domain);
            if (path != null)
                cookie.setPath(path);
            if (version > 0)
                cookie.setVersion(version);
            if (comment != null)
                cookie.setComment(comment);
            _cookieList.add(cookie);
        }
        catch (Exception e)
        {
            LOG.debug("Unable to add Cookie name={}, value={}, domain={}, path={}, version={}, comment={}",
                name, value, domain, path, version, comment, e);
        }
    }
}
