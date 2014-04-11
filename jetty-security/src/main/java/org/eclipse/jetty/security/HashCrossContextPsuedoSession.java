//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.security;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @version $Rev: 4660 $ $Date: 2009-02-25 17:29:53 +0100 (Wed, 25 Feb 2009) $
 */
public class HashCrossContextPsuedoSession<T> implements CrossContextPsuedoSession<T>
{
    private final String _cookieName;

    private final String _cookiePath;

    private final Random _random = new SecureRandom();

    private final Map<String, T> _data = new HashMap<String, T>();

    public HashCrossContextPsuedoSession(String cookieName, String cookiePath)
    {
        this._cookieName = cookieName;
        this._cookiePath = cookiePath == null ? "/" : cookiePath;
    }

    public T fetch(HttpServletRequest request)
    {
        for (Cookie cookie : request.getCookies())
        {
            if (_cookieName.equals(cookie.getName()))
            {
                String key = cookie.getValue();
                return _data.get(key);
            }
        }
        return null;
    }

    public void store(T datum, HttpServletResponse response)
    {
        String key;

        synchronized (_data)
        {
            // Create new ID
            while (true)
            {
                key = Long.toString(Math.abs(_random.nextLong()), 30 + (int) (System.currentTimeMillis() % 7));
                if (!_data.containsKey(key)) break;
            }

            _data.put(key, datum);
        }

        Cookie cookie = new Cookie(_cookieName, key);
        cookie.setPath(_cookiePath);
        response.addCookie(cookie);
    }

    public void clear(HttpServletRequest request)
    {
        for (Cookie cookie : request.getCookies())
        {
            if (_cookieName.equals(cookie.getName()))
            {
                String key = cookie.getValue();
                _data.remove(key);
                break;
            }
        }
    }
}
