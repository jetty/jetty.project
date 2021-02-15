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

package org.eclipse.jetty.deploy.providers.jmx;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.server.handler.jmx.AbstractHandlerMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("WebAppProvider mbean wrapper")
public class WebAppProviderMBean extends AbstractHandlerMBean
{
    public WebAppProviderMBean(Object managedObject)
    {
        super(managedObject);
    }

    @ManagedAttribute("List of monitored resources")
    public List<String> getMonitoredResources()
    {
        return ((WebAppProvider)_managed).getMonitoredResources().stream()
            .map((r) -> r.getURI().toASCIIString())
            .collect(Collectors.toList());
    }
}
