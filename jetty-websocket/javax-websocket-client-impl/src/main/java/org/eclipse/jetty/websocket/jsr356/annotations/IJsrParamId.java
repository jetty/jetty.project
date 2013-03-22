//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
     * Primary purpose is to indicate if this is a valid parameter or not by returning true for a known valid parameter type, false for a parameter type that
     * was not handled by this IJsrParamId. And throwing a InvalidSignatureException if the parameter violates a rule that the IJsrParamId is aware of.
     * 
     * @param type
     *            the parameter type being processed
     * @param method
     *            the method this type belongs to
     * @param metadata
     *            the metadata for this pojo
     * 
     * @return true if processed, false if not processed
     * @throws InvalidSignatureException
     *             if a violation of the signature rules occurred
     */
    boolean process(Class<?> type, IJsrMethod method, JsrMetadata<?> metadata) throws InvalidSignatureException;
}
