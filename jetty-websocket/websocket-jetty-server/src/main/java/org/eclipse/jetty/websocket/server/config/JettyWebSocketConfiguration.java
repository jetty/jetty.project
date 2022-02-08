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

package org.eclipse.jetty.websocket.server.config;

import org.eclipse.jetty.webapp.AbstractConfiguration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppConfiguration;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Websocket Configuration</p>
 * <p>This configuration configures the WebAppContext server/system classes to
 * be able to see the {@code org.eclipse.jetty.websocket.api}, {@code org.eclipse.jetty.websocket.server} and
 * {@code org.eclipse.jetty.websocket.servlet} packages.</p>
 */
public class JettyWebSocketConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketConfiguration.class);

    public JettyWebSocketConfiguration()
    {
        addDependencies(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class, FragmentConfiguration.class);
        addDependents("org.eclipse.jetty.annotations.AnnotationConfiguration", WebAppConfiguration.class.getName());

        protectAndExpose("org.eclipse.jetty.websocket.api.");
        protectAndExpose("org.eclipse.jetty.websocket.server.");
        protectAndExpose("org.eclipse.jetty.websocket.servlet."); // For WebSocketUpgradeFilter
        hide("org.eclipse.jetty.server.internal.");
        hide("org.eclipse.jetty.server.config.");
    }
}
