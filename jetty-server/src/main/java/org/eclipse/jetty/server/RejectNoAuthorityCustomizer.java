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
import org.eclipse.jetty.util.StringUtil;

/**
 * Reject requests that contain no request level authority.
 *
 * <p>
 * This addresses requests that either have schemes with no authority
 * per https://datatracker.ietf.org/doc/html/rfc7230#section-5.4
 * Or badly declared requests that have a non-absolute URI in the request line and an empty `Host` header.
 * </p>
 */
public class RejectNoAuthorityCustomizer implements HttpConfiguration.Customizer
{
    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        if (StringUtil.isBlank(request.getHttpURI().getAuthority()))
        {
            throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "No authority present");
        }
    }
}
