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

package org.eclipse.jetty.ee.common.internal;

import org.eclipse.jetty.ee.common.EnterpriseEditionVersion;

/**
 * {@link EnterpriseEditionVersion.Service} that derives the environment from the
 * {@link EnterpriseEditionVersion#ENVIRONMENT_PROPERTIES} marker resource.
 *
 * <p>The marker lives in the per-environment {@link ClassLoader} (eg: the {@code jetty-ee11-servlet}
 * module), so it is resolved via the {@link Thread#getContextClassLoader() thread context ClassLoader}
 * which during deployment, configuration and request handling is that per-environment ClassLoader.
 * The ClassLoader that defined this class cannot be used: under JPMS {@code jetty-ee-common} is a
 * single shared module whose ClassLoader cannot see the per-environment marker.</p>
 */
public class EnterpriseEditionMetaInfEnvService implements EnterpriseEditionVersion.Service
{
    @Override
    public EnterpriseEditionVersion getVersion()
    {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        EnterpriseEditionVersion version = EnterpriseEditionVersion.fromEnvironmentMarker(contextClassLoader);
        if (version == null)
        {
            ClassLoader localClassLoader = getClass().getClassLoader();
            if (localClassLoader != contextClassLoader)
                version = EnterpriseEditionVersion.fromEnvironmentMarker(localClassLoader);
        }
        return version;
    }
}
