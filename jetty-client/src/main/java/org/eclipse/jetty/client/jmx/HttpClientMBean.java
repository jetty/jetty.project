//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.jmx;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.jmx.ObjectMBean;

public class HttpClientMBean extends ObjectMBean
{
    public HttpClientMBean(Object managedObject)
    {
        super(managedObject);
    }

    @Override
    public String getObjectContextBasis()
    {
        // Returning the HttpClient name as the "context" property
        // because it is inherited by the ObjectNames of the components
        // of HttpClient such as the transport, the threadpool, etc.
        HttpClient httpClient = (HttpClient)getManagedObject();
        return httpClient.getName();
    }
}
