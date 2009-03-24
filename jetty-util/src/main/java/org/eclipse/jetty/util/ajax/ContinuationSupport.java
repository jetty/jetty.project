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

package org.eclipse.jetty.util.ajax;

import javax.servlet.http.HttpServletRequest;

/* ------------------------------------------------------------ */
/** ContinuationSupport.
 * Conveniance class to avoid classloading visibility issues.
 * 
 *
 */
public class ContinuationSupport
{
    public static Continuation getContinuation(HttpServletRequest request, Object mutex)
    {
        Continuation continuation = (Continuation) request.getAttribute("org.eclipse.jetty.ajax.Continuation");
        if (continuation==null)
            continuation=new WaitingContinuation(mutex);
        else 
            continuation.setMutex(mutex);
        return continuation;
    }
}
