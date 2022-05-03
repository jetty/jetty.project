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

package org.eclipse.jetty.ee10.cdi;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee10.webapp.AbstractConfiguration;

/**
 * <p>CDI Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see the {@link CdiServletContainerInitializer}.
 * </p>
 */
public class CdiConfiguration extends AbstractConfiguration
{
    public CdiConfiguration()
    {
        protectAndExpose("org.eclipse.jetty.ee10.cdi.CdiServletContainerInitializer");
        addDependents(AnnotationConfiguration.class, PlusConfiguration.class);
    }
}

