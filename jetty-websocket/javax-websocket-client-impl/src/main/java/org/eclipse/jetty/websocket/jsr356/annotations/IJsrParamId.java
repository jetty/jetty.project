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

package org.eclipse.jetty.websocket.jsr356.annotations;

import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;

/**
 * JSR-356 Parameter Identification processing.
 */
public interface IJsrParamId
{
    /**
     * Process the potential parameter.
     * <p>
     * If known to be a valid parameter, bind a role to it.
     * 
     * @param param
     *            the parameter being processed
     * @param callable
     *            the callable this param belongs to (used to obtain extra state about the callable that might impact decision making)
     * 
     * @return true if processed, false if not processed
     * @throws InvalidSignatureException
     *             if a violation of the signature rules occurred
     */
    boolean process(Param param, JsrCallable callable) throws InvalidSignatureException;
}
