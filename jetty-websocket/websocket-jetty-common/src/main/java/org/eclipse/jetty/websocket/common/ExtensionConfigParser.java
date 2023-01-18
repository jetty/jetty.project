//
// ========================================================================
// Copyright (c) 2019 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.websocket.core.ExtensionConfig;

public class ExtensionConfigParser implements org.eclipse.jetty.websocket.api.ExtensionConfig.Parser
{
    /**
     * Parse a single parameterized name.
     *
     * @param parameterizedName the parameterized name
     * @return the ExtensionConfig
     */
    @Override
    public JettyExtensionConfig parse(String parameterizedName)
    {
        return new JettyExtensionConfig(ExtensionConfig.parse(parameterizedName));
    }
}
