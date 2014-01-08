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

package org.eclipse.jetty.spdy.api;

import java.nio.channels.WritePendingException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

/**
 * <p>A {@link Stream} represents a bidirectional exchange of data on top of a {@link Session}.</p> <p>Differently from
 * socket streams, where the input and output streams are permanently associated with the socket (and hence with the
 * connection that the socket represents), there can be multiple SPDY streams for a SPDY session.</p> <p>SPDY streams
 * may terminate without this implying that the SPDY session is terminated.</p> <p>If SPDY is used to transport the HTTP
 * protocol, then a SPDY stream maps to a HTTP request/response cycle, and after the request/response cycle is
 * completed, the stream is closed, and other streams may be opened. Differently from HTTP, though, multiple SPDY
 * streams may be opened concurrently on the same SPDY session.</p> <p>Like {@link Session}, {@link Stream} is the
 * active part and by calling its API applications can generate events on the stream; conversely, {@link
 * StreamFrameListener} is the passive part, and its callbacks are invoked when events happen on the stream.</p> <p>A
 * {@link Stream} can send multiple data frames one after the other but implementations use a flow control mechanism
 * that only sends the data frames if the other end has signalled that it can accept the frame.</p> <p>Data frames
 * should be sent sequentially only when the previous frame has been completely sent. The reason for this requirement is
 * to avoid potentially confusing code such as:</p>
 * <pre>
 * // WRONG CODE, DO NOT USE IT
 * final Stream stream = ...;
 * stream.data(StringDataInfo("chunk1", false), 5, TimeUnit.SECONDS, new Handler&lt;Void&gt;() { ... });
 * stream.data(StringDataInfo("chunk2", true), 1, TimeUnit.SECONDS, new Handler&lt;Void&gt;() { ... });
 * </pre>
 * <p>where the second call to {@link #data(DataInfo, Callback)} has a timeout smaller than the previous call.</p>
 * <p>The behavior of such style of invocations is unspecified (it may even throw an exception - similar to {@link
 * WritePendingException}).</p> <p>The correct sending of data frames is the following:</p>
 * <pre>
 * final Stream stream = ...;
 * ...
 * // Blocking version
 * stream.data(new StringDataInfo("chunk1", false)).get(1, TimeUnit.SECONDS);
 * stream.data(new StringDataInfo("chunk2", true)).get(1, TimeUnit.SECONDS);
 *
 * // Asynchronous version
 * stream.data(new StringDataInfo("chunk1", false), 1, TimeUnit.SECONDS, new Handler.Adapter&lt;Void&gt;()
 * {
 *     public void completed(Void context)
 *     {
 *         stream.data(new StringDataInfo("chunk2", true));
 *     }
 * });
 * </pre>
 *
 * @see StreamFrameListener
 */
public interface Stream
{
    /**
     * @return the id of this stream
     */
    public int getId();

    /**
     * @return the priority of this stream
     */
    public byte getPriority();

    /**
     * @return the session this stream is associated to
     */
    public Session getSession();

    /**
     * <p>Initiate a unidirectional spdy pushstream associated to this stream asynchronously<p> <p>Callers may use the
     * returned future to get the pushstream once it got created</p>
     *
     * @param pushInfo the metadata to send on stream creation
     * @return a future containing the stream once it got established
     * @see #push(PushInfo, Promise)
     */
    public Stream push(PushInfo pushInfo) throws InterruptedException, ExecutionException, TimeoutException;

    /**
     * <p>Initiate a unidirectional spdy pushstream associated to this stream asynchronously<p> <p>Callers may pass a
     * non-null completion promise to be notified of when the pushstream has been established.</p>
     *
     * @param pushInfo the metadata to send on stream creation
     * @param promise the completion promise that gets notified once the pushstream is established
     * @see #push(PushInfo)
     */
    public void push(PushInfo pushInfo, Promise<Stream> promise);

    /**
     * <p>Sends asynchronously a SYN_REPLY frame in response to a SYN_STREAM frame.</p> <p>Callers may use the returned
     * future to wait for the reply to be actually sent.</p>
     *
     * @param replyInfo the metadata to send
     * @see #reply(ReplyInfo, Callback)
     * @see SessionFrameListener#onSyn(Stream, SynInfo)
     */
    public void reply(ReplyInfo replyInfo) throws InterruptedException, ExecutionException, TimeoutException;

    /**
     * <p>Sends asynchronously a SYN_REPLY frame in response to a SYN_STREAM frame.</p> <p>Callers may pass a non-null
     * completion callback to be notified of when the reply has been actually sent.</p>
     *
     * @param replyInfo the metadata to send
     * @param callback  the completion callback that gets notified of reply sent
     * @see #reply(ReplyInfo)
     */
    public void reply(ReplyInfo replyInfo, Callback callback);

    /**
     * <p>Sends asynchronously a DATA frame on this stream.</p> <p>DATA frames should always be sent after a SYN_REPLY
     * frame.</p> <p>Callers may use the returned future to wait for the data to be actually sent.</p>
     *
     * @param dataInfo the metadata to send
     * @see #data(DataInfo, Callback)
     * @see #reply(ReplyInfo)
     */
    public void data(DataInfo dataInfo) throws InterruptedException, ExecutionException, TimeoutException;

    /**
     * <p>Sends asynchronously a DATA frame on this stream.</p> <p>DATA frames should always be sent after a SYN_REPLY
     * frame.</p> <p>Callers may pass a non-null completion callback to be notified of when the data has been actually
     * sent.</p>
     *
     * @param dataInfo the metadata to send
     * @param callback the completion callback that gets notified of data sent
     * @see #data(DataInfo)
     */
    public void data(DataInfo dataInfo, Callback callback);

    /**
     * <p>Sends asynchronously a HEADER frame on this stream.</p> <p>HEADERS frames should always be sent after a
     * SYN_REPLY frame.</p> <p>Callers may use the returned future to wait for the headers to be actually sent.</p>
     *
     * @param headersInfo the metadata to send
     * @see #headers(HeadersInfo, Callback)
     * @see #reply(ReplyInfo)
     */
    public void headers(HeadersInfo headersInfo) throws InterruptedException, ExecutionException, TimeoutException;

    /**
     * <p>Sends asynchronously a HEADER frame on this stream.</p> <p>HEADERS frames should always be sent after a
     * SYN_REPLY frame.</p> <p>Callers may pass a non-null completion callback to be notified of when the headers have
     * been actually sent.</p>
     *
     * @param headersInfo the metadata to send
     * @param callback    the completion callback that gets notified of headers sent
     * @see #headers(HeadersInfo)
     */
    public void headers(HeadersInfo headersInfo, Callback callback);

    /**
     * @return whether this stream is unidirectional or not
     */
    public boolean isUnidirectional();

    /**
     * @return whether this stream has been reset
     */
    public boolean isReset();

    /**
     * @return whether this stream has been closed by both parties
     * @see #isHalfClosed()
     */
    public boolean isClosed();

    /**
     * @return whether this stream has been closed by one party only
     * @see #isClosed()
     */
    public boolean isHalfClosed();

    /**
     * @param key the attribute key
     * @return an arbitrary object associated with the given key to this stream or null if no object can be found for
     *         the given key.
     * @see #setAttribute(String, Object)
     */
    public Object getAttribute(String key);

    /**
     * @param key   the attribute key
     * @param value an arbitrary object to associate with the given key to this stream
     * @see #getAttribute(String)
     * @see #removeAttribute(String)
     */
    public void setAttribute(String key, Object value);

    /**
     * @param key the attribute key
     * @return the arbitrary object associated with the given key to this stream
     * @see #setAttribute(String, Object)
     */
    public Object removeAttribute(String key);

    /**
     * @return the associated parent stream or null if this is not an associated stream
     */
    public Stream getAssociatedStream();

    /**
     * @return associated child streams or an empty set if no associated streams exist
     */
    public Set<Stream> getPushedStreams();

    /**
     * Get the idle timeout set for this particular stream
     * @return the idle timeout
     */
    public long getIdleTimeout();

    /**
     * Set an idle timeout for this stream
     * @param timeout
     */
    public void setIdleTimeout(long timeout);

}
