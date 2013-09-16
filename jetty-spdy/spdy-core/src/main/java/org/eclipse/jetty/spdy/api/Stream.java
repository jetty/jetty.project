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

package org.eclipse.jetty.spdy.api;

import java.nio.channels.WritePendingException;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * <p>A {@link Stream} represents a bidirectional exchange of data on top of a {@link Session}.</p>
 * <p>Differently from socket streams, where the input and output streams are permanently associated
 * with the socket (and hence with the connection that the socket represents), there can be multiple
 * SPDY streams for a SPDY session.</p>
 * <p>SPDY streams may terminate without this implying that the SPDY session is terminated.</p>
 * <p>If SPDY is used to transport the HTTP protocol, then a SPDY stream maps to a HTTP request/response
 * cycle, and after the request/response cycle is completed, the stream is closed, and other streams
 * may be opened. Differently from HTTP, though, multiple SPDY streams may be opened concurrently
 * on the same SPDY session.</p>
 * <p>Like {@link Session}, {@link Stream} is the active part and by calling its API applications
 * can generate events on the stream; conversely, {@link StreamFrameListener} is the passive part, and its
 * callbacks are invoked when events happen on the stream.</p>
 * <p>A {@link Stream} can send multiple data frames one after the other but implementations use a
 * flow control mechanism that only sends the data frames if the other end has signalled that it can
 * accept the frame.</p>
 * <p>Data frames should be sent sequentially only when the previous frame has been completely sent.
 * The reason for this requirement is to avoid potentially confusing code such as:</p>
 * <pre>
 * // WRONG CODE, DO NOT USE IT
 * final Stream stream = ...;
 * stream.data(StringDataInfo("chunk1", false), 5, TimeUnit.SECONDS, new Handler&lt;Void&gt;() { ... });
 * stream.data(StringDataInfo("chunk2", true), 1, TimeUnit.SECONDS, new Handler&lt;Void&gt;() { ... });
 * </pre>
 * <p>where the second call to {@link #data(DataInfo, long, TimeUnit, Handler)} has a timeout smaller
 * than the previous call.</p>
 * <p>The behavior of such style of invocations is unspecified (it may even throw an exception - similar
 * to {@link WritePendingException}).</p>
 * <p>The correct sending of data frames is the following:</p>
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
     * <p>Initiate a unidirectional spdy pushstream associated to this stream asynchronously<p>
     * <p>Callers may use the returned future to get the pushstream once it got created</p>
     *
     * @param synInfo the metadata to send on stream creation
     * @return a future containing the stream once it got established
     * @see #syn(SynInfo, long, TimeUnit, Handler)
     */
    public Future<Stream> syn(SynInfo synInfo);

    /**
     * <p>Initiate a unidirectional spdy pushstream associated to this stream asynchronously<p>
     * <p>Callers may pass a non-null completion handler to be notified of when the
     * pushstream has been established.</p>
     *
     * @param synInfo the metadata to send on stream creation
     * @param timeout  the operation's timeout
     * @param unit     the timeout's unit
     * @param handler   the completion handler that gets notified once the pushstream is established
     * @see #syn(SynInfo)
     */
    public void syn(SynInfo synInfo, long timeout, TimeUnit unit, Handler<Stream> handler);

    /**
     * <p>Sends asynchronously a SYN_REPLY frame in response to a SYN_STREAM frame.</p>
     * <p>Callers may use the returned future to wait for the reply to be actually sent.</p>
     *
     * @param replyInfo the metadata to send
     * @return a future to wait for the reply to be sent
     * @see #reply(ReplyInfo, long, TimeUnit, Handler)
     * @see SessionFrameListener#onSyn(Stream, SynInfo)
     */
    public Future<Void> reply(ReplyInfo replyInfo);

    /**
     * <p>Sends asynchronously a SYN_REPLY frame in response to a SYN_STREAM frame.</p>
     * <p>Callers may pass a non-null completion handler to be notified of when the
     * reply has been actually sent.</p>
     *
     * @param replyInfo the metadata to send
     * @param timeout  the operation's timeout
     * @param unit     the timeout's unit
     * @param handler   the completion handler that gets notified of reply sent
     * @see #reply(ReplyInfo)
     */
    public void reply(ReplyInfo replyInfo, long timeout, TimeUnit unit, Handler<Void> handler);

    /**
     * <p>Sends asynchronously a DATA frame on this stream.</p>
     * <p>DATA frames should always be sent after a SYN_REPLY frame.</p>
     * <p>Callers may use the returned future to wait for the data to be actually sent.</p>
     *
     * @param dataInfo the metadata to send
     * @return a future to wait for the data to be sent
     * @see #data(DataInfo, long, TimeUnit, Handler)
     * @see #reply(ReplyInfo)
     */
    public Future<Void> data(DataInfo dataInfo);

    /**
     * <p>Sends asynchronously a DATA frame on this stream.</p>
     * <p>DATA frames should always be sent after a SYN_REPLY frame.</p>
     * <p>Callers may pass a non-null completion handler to be notified of when the
     * data has been actually sent.</p>
     *
     * @param dataInfo the metadata to send
     * @param timeout  the operation's timeout
     * @param unit     the timeout's unit
     * @param handler  the completion handler that gets notified of data sent
     * @see #data(DataInfo)
     */
    public void data(DataInfo dataInfo, long timeout, TimeUnit unit, Handler<Void> handler);

    /**
     * <p>Sends asynchronously a HEADER frame on this stream.</p>
     * <p>HEADERS frames should always be sent after a SYN_REPLY frame.</p>
     * <p>Callers may use the returned future to wait for the headers to be actually sent.</p>
     *
     * @param headersInfo the metadata to send
     * @return a future to wait for the headers to be sent
     * @see #headers(HeadersInfo, long, TimeUnit, Handler)
     * @see #reply(ReplyInfo)
     */
    public Future<Void> headers(HeadersInfo headersInfo);

    /**
     * <p>Sends asynchronously a HEADER frame on this stream.</p>
     * <p>HEADERS frames should always be sent after a SYN_REPLY frame.</p>
     * <p>Callers may pass a non-null completion handler to be notified of when the
     * headers have been actually sent.</p>
     *
     * @param headersInfo the metadata to send
     * @param timeout  the operation's timeout
     * @param unit     the timeout's unit
     * @param handler     the completion handler that gets notified of headers sent
     * @see #headers(HeadersInfo)
     */
    public void headers(HeadersInfo headersInfo, long timeout, TimeUnit unit, Handler<Void> handler);

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
     * @return an arbitrary object associated with the given key to this stream
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

}
