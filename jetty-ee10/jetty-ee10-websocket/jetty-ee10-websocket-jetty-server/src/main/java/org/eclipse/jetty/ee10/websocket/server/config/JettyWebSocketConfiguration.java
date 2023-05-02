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

package org.eclipse.jetty.ee10.websocket.server.config;

import org.eclipse.jetty.ee10.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee10.webapp.FragmentConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppConfiguration;
import org.eclipse.jetty.ee10.webapp.WebInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebXmlConfiguration;

/**
 * <p>Websocket Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see the {@code org.eclipse.jetty.ee10.websocket.api}, {@code org.eclipse.jetty.websocket.server} and
 * {@code org.eclipse.jetty.websocket.servlet} packages.</p>
 */
public class JettyWebSocketConfiguration extends AbstractConfiguration
{
    public JettyWebSocketConfiguration()
    {
        super(new Builder()
            .addDependencies(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class, FragmentConfiguration.class)
            .addDependents("org.eclipse.jetty.ee10.annotations.AnnotationConfiguration", WebAppConfiguration.class.getName())
            .protectAndExpose("org.eclipse.jetty.websocket.api.")
            .protectAndExpose("org.eclipse.jetty.websocket.server.")
            .protectAndExpose("org.eclipse.jetty.ee10.websocket.server.")
            .protectAndExpose("org.eclipse.jetty.ee10.websocket.servlet.") // For WebSocketUpgradeFilter
            .hide("org.eclipse.jetty.websocket.server.internal.")
            .hide("org.eclipse.jetty.ee10.websocket.server.internal.")
            .hide("org.eclipse.jetty.ee10.websocket.server.config."));
    }
}
