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
 * <p>An {@link Connection} is associated to an {@link EndPoint} so that I/O events
 * happening on the {@link EndPoint} can be processed by the {@link Connection}.</p>
 * <p>A typical implementation of {@link Connection} overrides {@link #onOpen()} to
 * {@link EndPoint#fillInterested(Object, Callback) set read interest} on the {@link EndPoint},
 * and when the {@link EndPoint} signals read readyness, this {@link Connection} can
 * read bytes from the network and interpret them.</p>
 */
public interface Connection
{
    /**
     * <p>Callback method invoked when this {@link Connection} is opened.</p>
     */
    void onOpen();

    /**
     * <p>Callback method invoked when this {@link Connection} is closed.</p>
     */
    void onClose();

    /**
     * @return the {@link EndPoint} associated with this {@link Connection}
     */
    EndPoint getEndPoint();
}
