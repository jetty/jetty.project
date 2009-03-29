// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.continuation;

import javax.servlet.ServletRequest;

/* ------------------------------------------------------------ */
/** ContinuationSupport.
 * 
 */
public class ContinuationSupport
{
    public static Continuation getContinuation(ServletRequest request)
    {
        Continuation continuation = (Continuation) request.getAttribute(Continuation.ATTRIBUTE);
        if (continuation==null)
            throw new IllegalStateException("!Jetty && !ContinuationFilter installed");
        return continuation;
    }
}
