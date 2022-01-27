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

package org.eclipse.jetty.deploy.providers.jmx;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.providers.WebAppProvider;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("WebAppProvider mbean wrapper")
public class WebAppProviderMBean extends ObjectMBean
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
