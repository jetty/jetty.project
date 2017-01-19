//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.pathmap;

/**
 * @deprecated moved to jetty-http {@link org.eclipse.jetty.http.pathmap.PathSpec} (this facade will be removed in Jetty 9.4)
 */
@Deprecated
public abstract class PathSpec
{
    private final String spec;
    
    protected PathSpec(String spec)
    {
        this.spec = spec;
    }
    
    public String getSpec()
    {
        return spec;
    }
}
