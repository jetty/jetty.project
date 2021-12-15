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
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.util.StringUtil;

/**
 * Reject requests that have no request level authority.
 *
 * <p>
 * This addresses requests that either have schemes with no authority
 * per https://datatracker.ietf.org/doc/html/rfc7230#section-5.4
 * Or badly declared requests that have a non-absolute URI in the request line and an empty or missing `Host` header.
 * </p>
 *
 * <p>
 * This will return a 400 Bad Request on failed authority checks.
 * </p>
 */
public class RejectMissingAuthorityCustomizer implements HttpConfiguration.Customizer
{
    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        if (!hasRequestAuthority(request))
        {
            throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Missing authority");
        }
    }

    private boolean hasRequestAuthority(Request request)
    {
        MetaData.Request metadata = request.getMetaData();

        if (metadata != null)
        {
            // Investigate HttpURI (eg: from HTTP/2 :authority)?
            if (StringUtil.isNotBlank(metadata.getURI().getHost()))
                return true;

            // Investigate Host Header (eg: from HTTP/1 request fields)
            HttpField host = metadata.getFields().getField(HttpHeader.HOST);
            if (host != null)
            {
                if (!(host instanceof HostPortHttpField) && StringUtil.isNotBlank(host.getValue()))
                {
                    return true;
                }

                if (host instanceof HostPortHttpField)
                {
                    HostPortHttpField authority = (HostPortHttpField)host;
                    metadata.getURI().setAuthority(authority.getHost(), authority.getPort());
                    if (StringUtil.isNotBlank(authority.getHost()))
                        return true;
                }
            }
        }

        return false;
    }
}
