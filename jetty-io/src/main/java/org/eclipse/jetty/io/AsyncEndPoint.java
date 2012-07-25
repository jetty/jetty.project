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

import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExecutorCallback;
import org.eclipse.jetty.util.FutureCallback;

/**
 * <p>{@link AsyncEndPoint} add asynchronous scheduling methods to {@link EndPoint}.</p>
 * <p>The design of these has been influenced by NIO.2 Futures and Completion
 * handlers, but does not use those actual interfaces because they have
 * some inefficiencies.</p>
 * <p>This class will frequently be used in conjunction with some of the utility
 * implementations of {@link Callback}, such as {@link FutureCallback} and
 * {@link ExecutorCallback}. Examples are:</p>
 *
 * <h3>Blocking Read</h3>
 * <p>A FutureCallback can be used to block until an endpoint is ready to be filled
 * from:
 * <blockquote><pre>
 * FutureCallback&lt;String&gt; future = new FutureCallback&lt;&gt;();
 * endpoint.fillInterested("ContextObj",future);
 * ...
 * String context = future.get(); // This blocks
 * int filled=endpoint.fill(mybuffer);
 * </pre></blockquote></p>
 *
 * <h3>Dispatched Read</h3>
 * <p>By using a different callback, the read can be done asynchronously in its own dispatched thread:
 * <blockquote><pre>
 * endpoint.fillInterested("ContextObj",new ExecutorCallback&lt;String&gt;(executor)
 * {
 *   public void onCompleted(String context)
 *   {
 *     int filled=endpoint.fill(mybuffer);
 *     ...
 *   }
 *   public void onFailed(String context,Throwable cause) {...}
 * });
 * </pre></blockquote></p>
 * <p>The executor callback can also be customized to not dispatch in some circumstances when
 * it knows it can use the callback thread and does not need to dispatch.</p>
 *
 * <h3>Blocking Write</h3>
 * <p>The write contract is that the callback complete is not called until all data has been
 * written or there is a failure.  For blocking this looks like:
 * <blockquote><pre>
 * FutureCallback&lt;String&gt; future = new FutureCallback&lt;&gt;();
 * endpoint.write("ContextObj",future,headerBuffer,contentBuffer);
 * String context = future.get(); // This blocks
 * </pre></blockquote></p>
 *
 * <h3>Dispatched Write</h3>
 * <p>Note also that multiple buffers may be passed in write so that gather writes
 * can be done:
 * <blockquote><pre>
 * endpoint.write("ContextObj",new ExecutorCallback&lt;String&gt;(executor)
 * {
 *   public void onCompleted(String context)
 *   {
 *     int filled=endpoint.fill(mybuffer);
 *     ...
 *   }
 *   public void onFailed(String context,Throwable cause) {...}
 * },headerBuffer,contentBuffer);
 * </pre></blockquote></p>
 */
public interface AsyncEndPoint extends EndPoint
{
    /**
     * <p>Requests callback methods to be invoked when a call to {@link #fill(ByteBuffer)} would return data or EOF.</p>
     *
     * @param context the context to return via the callback
     * @param callback the callback to call when an error occurs or we are readable.
     * @throws ReadPendingException if another read operation is concurrent.
     */
    <C> void fillInterested(C context, Callback<C> callback) throws ReadPendingException;

    /**
     * <p>Writes the given buffers via {@link #flush(ByteBuffer...)} and invokes callback methods when either
     * all the data has been flushed or an error occurs.</p>
     *
     * @param context the context to return via the callback
     * @param callback the callback to call when an error occurs or the write completed.
     * @param buffers one or more {@link ByteBuffer}s that will be flushed.
     * @throws WritePendingException if another write operation is concurrent.
     */
    <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws WritePendingException;

    /**
     * @return the {@link AsyncConnection} associated with this {@link AsyncEndPoint}
     * @see #setAsyncConnection(AsyncConnection)
     */
    AsyncConnection getAsyncConnection();

    /**
     * @param connection the {@link AsyncConnection} associated with this {@link AsyncEndPoint}
     * @see #getAsyncConnection()
     */
    void setAsyncConnection(AsyncConnection connection);

    /**
     * <p>Callback method invoked when this {@link AsyncEndPoint} is opened.</p>
     * @see #onClose()
     */
    void onOpen();

    /**
     * <p>Callback method invoked when this {@link AsyncEndPoint} is close.</p>
     * @see #onOpen()
     */
    void onClose();
}
