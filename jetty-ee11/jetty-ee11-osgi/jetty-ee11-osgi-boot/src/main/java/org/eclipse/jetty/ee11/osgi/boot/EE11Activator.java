//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.osgi.boot;

import org.eclipse.jetty.ee.osgi.boot.EEActivator;
import org.eclipse.jetty.ee.webapp.WebAppContext;

/**
 * EE11Activator
 * <p>
 * Enable deployment of webapps/contexts to EE11
 */
public class EE11Activator extends EEActivator
{
    public static final String ENVIRONMENT = "ee11";

    @Override
    public String getEnvironment()
    {
        return ENVIRONMENT;
    }

    @Override
    protected WebAppContext newWebAppContext()
    {
        return new org.eclipse.jetty.ee11.webapp.WebAppContext();
    }
}
