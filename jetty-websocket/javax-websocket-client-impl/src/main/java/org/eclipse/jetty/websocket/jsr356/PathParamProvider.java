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

package org.eclipse.jetty.websocket.jsr356;

import java.util.Map;

/**
 * Optional interface for custom {@link javax.websocket.EndpointConfig} implementations
 * in Jetty to expose Path Param values used during the {@link org.eclipse.jetty.websocket.jsr356.function.JsrEndpointFunctions}
 * resolution of methods.
 * <p>
 *     Mostly a feature of the JSR356 Server implementation and its {@code &#064;javax.websocket.server.PathParam} annotation.
 * </p>
 */
public interface PathParamProvider
{
    Map<String,String> getPathParams();
}
