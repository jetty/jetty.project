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
