//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.continuation;

import java.util.EventListener;
import javax.servlet.ServletRequestListener;

/**
 * A Continuation Listener
 * <p>
 * A ContinuationListener may be registered with a call to
 * {@link Continuation#addContinuationListener(ContinuationListener)}.
 *
 * @deprecated use Servlet 3.0 {@link javax.servlet.AsyncContext} instead
 */
@Deprecated
public interface ContinuationListener extends EventListener
{

    /**
     * Called when a continuation life cycle is complete and after
     * any calls to {@link ServletRequestListener#requestDestroyed(javax.servlet.ServletRequestEvent)}
     * The response may still be written to during the call.
     *
     * @param continuation the continuation
     */
    void onComplete(Continuation continuation);

    /**
     * Called when a suspended continuation has timed out.
     * The response may be written to and the methods
     * {@link Continuation#resume()} or {@link Continuation#complete()}
     * may be called by a onTimeout implementation,
     *
     * @param continuation the continuation
     */
    void onTimeout(Continuation continuation);
}
