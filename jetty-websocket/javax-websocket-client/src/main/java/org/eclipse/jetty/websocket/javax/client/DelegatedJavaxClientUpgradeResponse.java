//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.client;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.javax.common.UpgradeResponse;

/**
 * Representing the Jetty {@link org.eclipse.jetty.client.HttpClient}'s {@link HttpResponse}
 * in the {@link UpgradeResponse} interface.
 */
public class DelegatedJavaxClientUpgradeResponse implements UpgradeResponse
{
    private HttpResponse delegate;

    public DelegatedJavaxClientUpgradeResponse(HttpResponse response)
    {
        this.delegate = response;
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return this.delegate.getHeaders().get(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        List<String> rawExtensions = delegate.getHeaders().getValuesList(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
        if (rawExtensions == null || rawExtensions.isEmpty())
            return Collections.emptyList();

        return rawExtensions.stream().map((parameterizedName) -> ExtensionConfig.parse(parameterizedName)).collect(Collectors.toList());
    }
}
