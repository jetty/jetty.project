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

package org.eclipse.jetty.ee10.cdi;

import java.util.function.Predicate;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee10.webapp.AbstractConfiguration;

/**
 * <p>CDI Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see the {@link CdiServletContainerInitializer}. Also hides the
 * jakarta cdi classes that are on the environment/server classpath and allows
 * the webapp to provide their own.
 * </p>
 */
public class CdiConfiguration extends AbstractConfiguration
{
    public CdiConfiguration()
    {
        super(new Builder()
            .protectAndExpose("org.eclipse.jetty.ee10.cdi.CdiServletContainerInitializer")
            .hide(getHiddenClasses())
            .addDependents(AnnotationConfiguration.class, PlusConfiguration.class));
    }

    private static String[] getHiddenClasses()
    {
        //Only hide the cdi api classes if there is not also an impl on the
        //environment classpath - vital for embedded uses.
        if (CdiConfiguration.class.getClassLoader().getResource("META-INF/services/jakarta.enterprise.inject.spi.CDIProvider") == null)
            return new String[]{"jakarta.enterprise.", "jakarta.decorator."};
        return new String[0];
    }
}