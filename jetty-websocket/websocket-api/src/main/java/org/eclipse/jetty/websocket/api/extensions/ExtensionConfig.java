//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.api.extensions;

import java.util.Map;
import java.util.Set;

/**
 * Represents an Extension Configuration, as seen during the connection Handshake process.
 */
public interface ExtensionConfig
{
    public String getName();

    public int getParameter(String key, int defValue);

    public String getParameter(String key, String defValue);

    public String getParameterizedName();

    public Set<String> getParameterKeys();

    /**
     * Return parameters in way similar to how {@link javax.net.websocket.extensions.Extension#getParameters()} works.
     * 
     * @return the parameter map
     */
    public Map<String, String> getParameters();

    public void setParameter(String key, int value);

    public void setParameter(String key, String value);
}