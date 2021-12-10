//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.StringUtil;

/**
 * Reject requests that have issues with their request level authority.
 *
 * <p>
 * This addresses requests that either have schemes with no authority
 * per https://datatracker.ietf.org/doc/html/rfc7230#section-5.4
 * Or badly declared requests that have a non-absolute URI in the request line and an empty `Host` header.
 * </p>
 *
 * <p>
 * This will return a 400 Bad Request on failed authority checks.
 * </p>
 */
public class RejectInvalidAuthorityCustomizer implements HttpConfiguration.Customizer
{
    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        if (!isValidAuthority(request.getHttpURI()))
        {
            throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "No authority present");
        }
    }

    private boolean isValidAuthority(HttpURI httpURI)
    {
        String authority = httpURI.getAuthority();
        if (StringUtil.isBlank(authority))
            return false;

        String host = httpURI.getHost();
        if (StringUtil.isBlank(host))
            return false;
        else
        {
            // Host must start with alpha-numeric.
            int c = host.codePointAt(0);
            if (!Character.isLetterOrDigit(c))
                return false;
            // the rest of the characters are limited to alpha-numeric, dot, dash and nothing else
            for (int i = 1; i < host.length(); i++)
            {
                c = host.codePointAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-'))
                    return false;
            }
        }

        int port = httpURI.getPort();
        return (port >= 1) && (port <= 65535);
    }
}
