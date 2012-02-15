/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.api;

import java.util.EventListener;

/**
 * <p>A {@link Stream} represents an bidirectional exchange of data on top of a {@link Session}.</p>
 * <p>Differently from socket streams, where the input and output streams are permanently associated
 * with the socket (and hence with the connection that the socket represents), there can be multiple
 * SPDY streams for a SPDY session.</p>
 * <p>SPDY streams may terminate without this implying that the SPDY session is terminated.</p>
 * <p>If SPDY is used to transport the HTTP protocol, then a SPDY stream maps to a HTTP request/response
 * cycle, and after the request/response cycle is completed, the stream is closed, and other streams
 * may be opened. Differently from HTTP, though, multiple SPDY streams may be opened concurrently
 * on the same SPDY session.</p>
 * <p>Like {@link Session}, {@link Stream} is the active part and by calling its API applications
 * can generate events on the stream; conversely, {@link FrameListener} is the passive part, and its
 * callbacks are invoked when events happen on the stream</p>
 *
 * @see FrameListener
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
     * <p>Sends a SYN_REPLY frame in response to a SYN_STREAM frame.</p>
     *
     * @param replyInfo the metadata to send
     * @see Session.FrameListener#onSyn(Stream, SynInfo)
     */
    public void reply(ReplyInfo replyInfo);

    /**
     * <p>Sends a DATA frame on this stream.</p>
     * <p>DATA frames should always be sent after a SYN_REPLY frame.</p>
     *
     * @param dataInfo the metadata to send
     * @see #reply(ReplyInfo)
     */
    public void data(DataInfo dataInfo);

    /**
     * <p>Sends a HEADER frame on this stream.</p>
     * <p>HEADERS frames should always be sent after a SYN_REPLY frame.</p>
     *
     * @param headersInfo the metadata to send
     * @see #reply(ReplyInfo)
     */
    public void headers(HeadersInfo headersInfo);

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
     * @param key the attribute key
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
     * <p>A {@link FrameListener} is the passive counterpart of a {@link Stream} and receives
     * events happening on a SPDY stream.</p>
     *
     * @see Stream
     */
    public interface FrameListener extends EventListener
    {
        /**
         * <p>Callback invoked when a reply to a stream creation has been received.</p>
         * <p>Application code may implement this method to send more data to the other end:</p>
         * <pre>
         * public void onReply(Stream stream, ReplyInfo replyInfo)
         * {
         *     stream.data(new StringDataInfo("content"), true);
         * }
         * </pre>
         * @param stream the stream
         * @param replyInfo the reply metadata
         */
        public void onReply(Stream stream, ReplyInfo replyInfo);

        /**
         * <p>Callback invoked when headers are received on a stream.</p>
         *
         * @param stream the stream
         * @param headersInfo the headers metadata
         */
        public void onHeaders(Stream stream, HeadersInfo headersInfo);

        /**
         * <p>Callback invoked when data are received on a stream.</p>
         *
         * @param stream the stream
         * @param dataInfo the data metadata
         */
        public void onData(Stream stream, DataInfo dataInfo);

        /**
         * <p>Empty implementation of {@link FrameListener}</p>
         */
        public static class Adapter implements FrameListener
        {
            @Override
            public void onReply(Stream stream, ReplyInfo replyInfo)
            {
            }

            @Override
            public void onHeaders(Stream stream, HeadersInfo headersInfo)
            {
            }

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
            }
        }
    }
}
