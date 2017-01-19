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
 * @version $Revision$ $Date$
 */
public class HostTargetIdRetriever implements TargetIdRetriever
{
    private final String suffix;

    public HostTargetIdRetriever(String suffix)
    {
        this.suffix = suffix;
    }

    public String retrieveTargetId(HttpServletRequest httpRequest)
    {
        String host = httpRequest.getHeader("Host");
        if (host != null)
        {
            // Strip the port
            int colon = host.indexOf(':');
            if (colon > 0)
            {
                host = host.substring(0, colon);
            }

            if (suffix != null && host.endsWith(suffix))
            {
                return host.substring(0, host.length() - suffix.length());
            }
        }
        return host;
    }
}
