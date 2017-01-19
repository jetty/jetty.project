//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
 * <p>This implementation retrieves the targetId from the request URI following this pattern:</p>
 * <pre>
 * /contextPath/servletPath/&lt;targetId&gt;/other/paths
 * </pre>
 * @version $Revision$ $Date$
 */
public class StandardTargetIdRetriever implements TargetIdRetriever
{
    public String retrieveTargetId(HttpServletRequest httpRequest)
    {
        String uri = httpRequest.getRequestURI();
        String path = uri.substring(httpRequest.getServletPath().length());
        String[] segments = path.split("/");
        if (segments.length < 2) return null;
        return segments[1];
    }
}
