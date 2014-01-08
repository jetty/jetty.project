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

import java.util.EventListener;

/**
 * <p>A {@link StreamFrameListener} is the passive counterpart of a {@link Stream} and receives
 * events happening on a SPDY stream.</p>
 *
 * @see Stream
 */
public interface StreamFrameListener extends EventListener
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
     * <p>Callback invoked when a push syn has been received on a stream.</p>
     *
     * @param stream the push stream just created
     * @param pushInfo the push metadata
     * @return a listener for stream events or null if there is no interest in being notified of stream events
     */
    public StreamFrameListener onPush(Stream stream, PushInfo pushInfo);

    /**
     * <p>Callback invoked when data bytes are received on a stream.</p>
     * <p>Implementers should be read or consume the content of the
     * {@link DataInfo} before this method returns.</p>
     *
     * @param stream the stream
     * @param dataInfo the data metadata
     */
    public void onData(Stream stream, DataInfo dataInfo);

    /**
     * <p>Callback invoked on errors.</p>
     * @param stream the stream
     * @param x the failure
     */
    public void onFailure(Stream stream, Throwable x);

    /**
     * <p>Empty implementation of {@link StreamFrameListener}</p>
     */
    public static class Adapter implements StreamFrameListener
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
        public StreamFrameListener onPush(Stream stream, PushInfo pushInfo)
        {
            return null;
        }

        @Override
        public void onData(Stream stream, DataInfo dataInfo)
        {
        }

        @Override
        public void onFailure(Stream stream, Throwable x)
        {
        }
    }
}
