//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2;

import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.util.Callback;

public interface IStream extends Stream
{
    @Override
    public ISession getSession();

    public Listener getListener();

    public void setListener(Listener listener);

    public boolean process(Frame frame, Callback callback);

    /**
     * Updates the close state of this stream.
     *
     * @param update whether to update the close state
     * @param local whether the update comes from a local operation
     *              (such as sending a frame that ends the stream)
     *              or a remote operation (such as receiving a frame
     *              that ends the stream).
     */
    public void updateClose(boolean update, boolean local);

    public int getSendWindow();

    public int updateSendWindow(int delta);

    public int updateRecvWindow(int delta);

    public void close();
}
