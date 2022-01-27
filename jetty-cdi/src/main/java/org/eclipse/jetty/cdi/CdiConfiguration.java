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

package org.eclipse.jetty.cdi;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>CDI Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see the {@link CdiServletContainerInitializer}.
 * </p>
 */
public class CdiConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(CdiConfiguration.class);

    public CdiConfiguration()
    {
        protectAndExpose("org.eclipse.jetty.cdi.CdiServletContainerInitializer");
        addDependents(AnnotationConfiguration.class, PlusConfiguration.class);
    }
}

