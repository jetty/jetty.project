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

package org.acme.webapp;

import java.util.List;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.WebAppContext;

public class TestAnnotation extends DiscoveredAnnotation
{
    private List<TestAnnotation> applications;

    public TestAnnotation(WebAppContext context, String className, Resource resource, List<TestAnnotation> applications)
    {
        super(context, className, resource);
        this.applications = applications;
    }

    @Override
    public void apply()
    {
        if (applications != null)
            applications.add(this);
    }

    @Override
    public String toString()
    {
        return getClassName();
    }
}
