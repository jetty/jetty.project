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

package org.eclipse.jetty.websocket.api;

/**
 * Callback for Write events.
 */
public interface WriteCallback
{
    /*
     * NOTE: We don't expose org.eclipse.jetty.util.Callback here as that would complicate matters with the WebAppContext's classloader isolation.
     */

    /**
     * <p>
     * Callback invoked when the write fails.
     * </p>
     * 
     * @param x
     *            the reason for the write failure
     */
    public void writeFailed(Throwable x);

    /**
     * <p>
     * Callback invoked when the write completes.
     * </p>
     * 
     * @see #writeFailed(Throwable)
     */
    public abstract void writeSuccess();
}
