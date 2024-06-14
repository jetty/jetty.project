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

package org.eclipse.jetty.server;

import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.CookieParser;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cookie parser
 * <p>Optimized stateful cookie parser.
 * If the added fields are identical to those last added (as strings), then the
 * cookies are not re-parsed.
 * 
 */
public class CookieCache extends AbstractList<HttpCookie> implements CookieParser.Handler, ComplianceViolation.Listener
{
    /**
     * Get the core HttpCookies for a request.
     * Cookies may be cached as a {@link Request#getAttribute(String) request attribute}, failing that they may be
     * cached in the {@link Components#getCache() Component cache}, in which case they will be checked to see if they
     * have changed since a previous request.  Otherwise, they are parsed from the request headers and both caches updated.
     * @param request The request to obtain cookies from
     * @return A list of core {@link HttpCookie}s from the request.
     * @see #getApiCookies(Request, Class, Function)
     */
    public static List<HttpCookie> getCookies(Request request)
    {
        @SuppressWarnings("unchecked")
        List<HttpCookie> cookies = (List<HttpCookie>)request.getAttribute(Request.COOKIE_ATTRIBUTE);
        if (cookies != null)
            return cookies;

        CookieCache cookieCache = (CookieCache)request.getComponents().getCache().getAttribute(Request.COOKIE_ATTRIBUTE);
        if (cookieCache == null)
        {
            cookieCache = new CookieCache(request.getConnectionMetaData().getHttpConfiguration().getRequestCookieCompliance());
            request.getComponents().getCache().setAttribute(Request.COOKIE_ATTRIBUTE, cookieCache);
        }

        cookieCache.parseCookies(request.getHeaders(), HttpChannel.from(request).getComplianceViolationListener());
        request.setAttribute(Request.COOKIE_ATTRIBUTE, cookieCache);
        return cookieCache;
    }

    /**
     * Get the API specific cookies for a request.
     * Internally the same caching/parsing is done as by {@link #getCookies(Request)} and the core {@link HttpCookie}s are
     * obtained. The passed {@code convertor} function is used to covert the core {@link HttpCookie}s to API specific cookies
     * and the results cached along with the core {@link HttpCookie}s
     * @param request The request to get the cookies from.
     * @param cookieClass The class of the cookie API
     * @param convertor A function to convert from a {@link HttpCookie} to an API cookie of type {@code cookieClass}. The
     *                  function may return null if the cookie is not compliant with the API.
     * @param <C>       The class of the cookie API
     * @return          An array of API specific cookies.
     */
    public static <C> C[] getApiCookies(Request request, Class<C> cookieClass, Function<HttpCookie, C> convertor)
    {
        if (request == null)
            return null;

        CookieCache cookieCache = (CookieCache)request.getAttribute(Request.COOKIE_ATTRIBUTE);
        if (cookieCache == null)
        {
            cookieCache = (CookieCache)request.getComponents().getCache().getAttribute(Request.COOKIE_ATTRIBUTE);
            if (cookieCache == null)
            {
                cookieCache = new CookieCache(request.getConnectionMetaData().getHttpConfiguration().getRequestCookieCompliance());
                request.getComponents().getCache().setAttribute(Request.COOKIE_ATTRIBUTE, cookieCache);
            }

            cookieCache.parseCookies(request.getHeaders(), HttpChannel.from(request).getComplianceViolationListener());
            request.setAttribute(Request.COOKIE_ATTRIBUTE, cookieCache);
        }

        return cookieCache.getApiCookies(cookieClass, convertor);
    }

    protected static final Logger LOG = LoggerFactory.getLogger(CookieCache.class);
    protected final List<String> _rawFields = new ArrayList<>();
    private final CookieParser _parser;
    private List<HttpCookie> _httpCookies = Collections.emptyList();
    private  Map<Class<?>, Object[]> _apiCookies;
    private List<ComplianceViolation.Event> _violations;

    public CookieCache()
    {
        this(CookieCompliance.RFC6265);
    }

    public CookieCache(CookieCompliance compliance)
    {
        _parser = CookieParser.newParser(this, compliance, this);
    }

    @Override
    public HttpCookie get(int index)
    {
        return _httpCookies.get(index);
    }

    @Override
    public int size()
    {
        return _httpCookies.size();
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
            _httpCookies.add(HttpCookie.from(cookieName, cookieValue));
        else
        {
            Map<String, String> attributes = new HashMap<>();
            if (!StringUtil.isEmpty(cookieDomain))
                attributes.put(HttpCookie.DOMAIN_ATTRIBUTE, cookieDomain);
            if (!StringUtil.isEmpty(cookiePath))
                attributes.put(HttpCookie.PATH_ATTRIBUTE, cookiePath);
            if (!StringUtil.isEmpty(cookieComment))
                attributes.put(HttpCookie.COMMENT_ATTRIBUTE, cookieComment);
            _httpCookies.add(HttpCookie.from(cookieName, cookieValue, cookieVersion, attributes));
        }
    }

    List<HttpCookie> getCookies(HttpFields headers)
    {
        parseCookies(headers, ComplianceViolation.Listener.NOOP);
        return _httpCookies;
    }

    public void parseCookies(HttpFields headers, ComplianceViolation.Listener complianceViolationListener)
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
            _httpCookies = new ArrayList<>();
            _apiCookies = null;
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
    }

    public <C> C[] getApiCookies(Class<C> apiClass, Function<HttpCookie, C> convertor)
    {
        // No APIs if no Cookies
        if (_httpCookies.isEmpty())
            return null;

        // If only the core APIs have been used, then no apiCookie map has been created
        if (_apiCookies == null)
        {
            // When a cookie API is ued, the most common case in only a single API, so used a cheap Map
            C[] apiCookies = convert(apiClass, convertor);
            _apiCookies = Map.of(apiClass, apiCookies);
            return apiCookies;
        }

        @SuppressWarnings("unchecked")
        C[] apiCookies = (C[])_apiCookies.get(apiClass);
        if (apiCookies == null)
        {
            // Only in the case of cross environment dispatch will more than 1 API be needed, so only invest in a real
            // map when we know it is required.
            if (_apiCookies.size() == 1)
                _apiCookies = new HashMap<>(_apiCookies);
            apiCookies = convert(apiClass, convertor);
            _apiCookies.put(apiClass, apiCookies);
        }
        return apiCookies;
    }

    private <C> C[] convert(Class<C> apiClass, Function<HttpCookie, C> convertor)
    {
        @SuppressWarnings("unchecked")
        C[] apiCookies = (C[])Array.newInstance(apiClass, _httpCookies.size());
        int i = 0;
        for (HttpCookie httpCookie : _httpCookies)
        {
            C apiCookie = convertor.apply(httpCookie);
            // Exclude any API cookies that are not convertable to that API
            if (apiCookie == null)
                apiCookies = Arrays.copyOf(apiCookies, apiCookies.length - 1);
            else
                apiCookies[i++] = apiCookie;
        }
        return apiCookies;
    }
}
