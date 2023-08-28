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

package org.eclipse.jetty.ee9.cdi;

import org.eclipse.jetty.ee9.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee9.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee9.webapp.AbstractConfiguration;

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
        protectAndExpose("org.eclipse.jetty.ee9.cdi.CdiServletContainerInitializer");
        //Only hide the cdi api classes if there is not also an impl on the
        //environment classpath - vital for embedded uses.
        if (CdiConfiguration.class.getClassLoader().getResource("META-INF/services/jakarta.enterprise.inject.spi.CDIProvider") == null)
            hide("jakarta.enterprise.", "jakarta.decorator.");
        addDependents(AnnotationConfiguration.class, PlusConfiguration.class);
    }
}

