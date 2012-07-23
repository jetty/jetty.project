// ========================================================================
// Copyright (c) 2004-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;

import org.eclipse.jetty.util.Callback;

/**
 * <p>An {@link AsyncConnection} is associated to an {@link AsyncEndPoint} so that I/O events
 * happening on the {@link AsyncEndPoint} can be processed by the {@link AsyncConnection}.</p>
 * <p>A typical implementation of {@link AsyncConnection} overrides {@link #onOpen()} to
 * {@link AsyncEndPoint#fillInterested(Object, Callback) set read interest} on the {@link AsyncEndPoint},
 * and when the {@link AsyncEndPoint} signals read readyness, this {@link AsyncConnection} can
 * read bytes from the network and interpret them.</p>
 */
public interface AsyncConnection
{
    /**
     * <p>Callback method invoked when this {@link AsyncConnection} is opened.</p>
     */
    void onOpen();

    /**
     * <p>Callback method invoked when this {@link AsyncConnection} is closed.</p>
     */
    void onClose();

    /**
     * @return the {@link AsyncEndPoint} associated with this {@link AsyncConnection}
     */
    AsyncEndPoint getEndPoint();
}
