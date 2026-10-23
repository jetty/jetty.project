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

package org.eclipse.jetty.tests.tck.ee11;

import org.jboss.arquillian.container.jetty.embedded_12_1_ee11.WebAppContextProcessor;
import org.jboss.arquillian.core.spi.LoadableExtension;

/**
 * Registers the Jetty specific Arquillian hooks this TCK run needs.
 * Arquillian finds this class through META-INF/services.
 */
public class JettyLoadableExtension implements LoadableExtension
{
    @Override
    public void register(ExtensionBuilder extensionBuilder)
    {
        extensionBuilder.service(WebAppContextProcessor.class, JettyErrorHandlerWebAppContextProcessor.class);
    }
}
