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

package org.eclipse.jetty.ee9.osgi.boot;

import org.eclipse.jetty.ee9.webapp.Configuration;
import org.eclipse.jetty.ee9.webapp.WebInfConfiguration;

/**
 * OSGiWebInfConfiguration
 *
 * Handle adding resources found in bundle fragments, and add them into the
 */
public class OSGiWebInfConfiguration extends WebInfConfiguration
{
    @Override
    public Class<? extends Configuration> replaces()
    {
        return WebInfConfiguration.class;
    }
}
