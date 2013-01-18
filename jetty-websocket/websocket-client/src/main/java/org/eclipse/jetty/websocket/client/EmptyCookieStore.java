//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An empty implementation of the CookieStore
 */
public class EmptyCookieStore implements CookieStore
{
    private final List<HttpCookie> nocookies;
    private final List<URI> nouris;

    public EmptyCookieStore()
    {
        nocookies = Collections.unmodifiableList(new ArrayList<HttpCookie>());
        nouris = Collections.unmodifiableList(new ArrayList<URI>());
    }

    @Override
    public void add(URI uri, HttpCookie cookie)
    {
        /* do nothing */
    }

    @Override
    public List<HttpCookie> get(URI uri)
    {
        return nocookies;
    }

    @Override
    public List<HttpCookie> getCookies()
    {
        return nocookies;
    }

    @Override
    public List<URI> getURIs()
    {
        return nouris;
    }

    @Override
    public boolean remove(URI uri, HttpCookie cookie)
    {
        return false;
    }

    @Override
    public boolean removeAll()
    {
        return false;
    }
}
