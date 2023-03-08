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

package org.eclipse.jetty.websocket.javax.common;

import javax.websocket.Extension;

import org.eclipse.jetty.websocket.core.ExtensionConfig;

public class JavaxWebSocketExtensionConfig extends ExtensionConfig
{
    public JavaxWebSocketExtensionConfig(Extension ext)
    {
        super(ext.getName());
        for (Extension.Parameter param : ext.getParameters())
        {
            this.setParameter(param.getName(), param.getValue());
        }
    }
}
