/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket.extensions;

import org.eclipse.jetty.websocket.protocol.ExtensionConfig;


public class AbstractExtension implements Extension
{
    private final ExtensionConfig config;

    public AbstractExtension(String name)
    {
        this.config = new ExtensionConfig(name);
    }

    @Override
    public ExtensionConfig getConfig()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getName()
    {
        return config.getName();
    }

    @Override
    public String getParameterizedName()
    {
        return config.getParameterizedName();
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        this.config.init(config);
    }

    @Override
    public String toString()
    {
        return getParameterizedName();
    }
}
