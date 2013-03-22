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

public abstract class JsrParamIdOnMessage implements IJsrParamId
{
    protected void assertPartialMessageSupportDisabled(Class<?> type, IJsrMethod method)
    {
        if (method.isPartialMessageSupportEnabled())
        {
            StringBuilder err = new StringBuilder();
            err.append("Unable to support parameter type <");
            err.append(type.getName()).append("> in conjunction with the partial message indicator boolean.");
            err.append(" Only type <String> is supported with partial message boolean indicator.");
            throw new InvalidSignatureException(err.toString());
        }
    }
}
