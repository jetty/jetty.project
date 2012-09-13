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

package org.eclipse.jetty.client.api;

import java.util.List;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpCookie;

/**
 * A store for HTTP cookies that offers methods to match cookies for a given destination and path.
 *
 * @see HttpClient#getCookieStore()
 */
public interface CookieStore
{
    /**
     * Returns the non-expired cookies that match the given destination and path,
     * recursively matching parent paths (for the same domain) and parent domains
     * (for the root path).
     *
     * @param destination the destination representing the domain
     * @param path the request path
     * @return the list of matching cookies
     */
    List<HttpCookie> findCookies(Destination destination, String path);

    /**
     * Adds the given cookie to this store for the given destination.
     * If the cookie's domain and the destination host do not match, the cookie is not added.
     *
     * @param destination the destination the cookie should belong to
     * @param cookie the cookie to add
     * @return whether the cookie has been added or not
     */
    boolean addCookie(Destination destination, HttpCookie cookie);

    /**
     * Removes all the cookies from this store.
     */
    void clear();
}
