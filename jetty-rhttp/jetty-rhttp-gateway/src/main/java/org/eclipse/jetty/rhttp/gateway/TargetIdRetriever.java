//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.gateway;

import javax.servlet.http.HttpServletRequest;

/**
 * <p>Implementations should retrieve a <em>targetId</em> from an external request.</p>
 * <p>Implementations of this class may return a fixed value, or inspect the request
 * looking for URL patterns (e.g. "/&lt;targetId&gt;/resource.jsp"), or looking for request
 * parameters (e.g. "/resource.jsp?targetId=&lt;targetId&gt;), or looking for virtual host
 * naming patterns (e.g. "http://&lt;targetId&gt;.host.com/resource.jsp"), etc.</p>
 *
 * @version $Revision$ $Date$
 */
public interface TargetIdRetriever
{
    /**
     * Extracts and returns the targetId.
     * @param httpRequest the external request from where the targetId could be extracted
     * @return the extracted targetId
     */
    public String retrieveTargetId(HttpServletRequest httpRequest);
}
