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

package org.eclipse.jetty.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cookie parser
 * @deprecated Use {@code org.eclipse.jetty.server.CookieCache}
 */
@Deprecated (forRemoval = true)
public class CookieCache implements CookieParser.Handler, ComplianceViolation.Listener
{
    protected static final Logger LOG = LoggerFactory.getLogger(CookieCache.class);
    protected final List<String> _rawFields = new ArrayList<>();
    protected List<HttpCookie> _cookieList;
    private final CookieParser _parser;
    private List<ComplianceViolation.Event> _violations;

    @Deprecated
    public CookieCache()
    {
        this(CookieCompliance.RFC6265);
    }

    @Deprecated
    public CookieCache(CookieCompliance compliance)
    {
        _parser = CookieParser.newParser(this, compliance, this);
    }

    @Deprecated(forRemoval = true)
    public CookieCache(CookieCompliance compliance, ComplianceViolation.Listener complianceListener)
    {
        this(compliance);
    }

    @Override
    public void onComplianceViolation(ComplianceViolation.Event event)
    {
        if (_violations == null)
            _violations = new ArrayList<>();
        _violations.add(event);
    }

    @Override
    public void addCookie(String cookieName, String cookieValue, int cookieVersion, String cookieDomain, String cookiePath, String cookieComment)
    {
        if (StringUtil.isEmpty(cookieDomain) && StringUtil.isEmpty(cookiePath) && cookieVersion <= 0 && StringUtil.isEmpty(cookieComment))
            _cookieList.add(HttpCookie.from(cookieName, cookieValue));
        else
        {
            Map<String, String> attributes = new HashMap<>();
            if (!StringUtil.isEmpty(cookieDomain))
                attributes.put(HttpCookie.DOMAIN_ATTRIBUTE, cookieDomain);
            if (!StringUtil.isEmpty(cookiePath))
                attributes.put(HttpCookie.PATH_ATTRIBUTE, cookiePath);
            if (!StringUtil.isEmpty(cookieComment))
                attributes.put(HttpCookie.COMMENT_ATTRIBUTE, cookieComment);
            _cookieList.add(HttpCookie.from(cookieName, cookieValue, cookieVersion, attributes));
        }
    }

    public List<HttpCookie> getCookies(HttpFields headers)
    {
        return getCookies(headers, ComplianceViolation.Listener.NOOP);
    }

    public List<HttpCookie> getCookies(HttpFields headers, ComplianceViolation.Listener complianceViolationListener)
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
            try
            {
                if (_violations != null)
                    _violations.clear();
                _parser.parseFields(_rawFields);
            }
            catch (CookieParser.InvalidCookieException invalidCookieException)
            {
                throw new BadMessageException(invalidCookieException.getMessage(), invalidCookieException);
            }
        }

        if (_violations != null && !_violations.isEmpty())
            _violations.forEach(complianceViolationListener::onComplianceViolation);

        return _cookieList == null ? Collections.emptyList() : _cookieList;
    }

    /**
     * Replace the cookie list with
     * @param cookies The replacement cookie list, which must be equal to the existing list
     */
    public void replaceCookieList(List<HttpCookie> cookies)
    {
        assert _cookieList.equals(cookies);
        _cookieList = cookies;
    }
}
