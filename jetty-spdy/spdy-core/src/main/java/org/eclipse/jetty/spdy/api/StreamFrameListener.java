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
     * <p>Callback invoked when data bytes are received on a stream.</p>
     * <p>Implementers should be read or consume the content of the
     * {@link DataInfo} before this method returns.</p>
     *
     * @param stream the stream
     * @param dataInfo the data metadata
     */
    public void onData(Stream stream, DataInfo dataInfo);

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
        public void onData(Stream stream, DataInfo dataInfo)
        {
        }
    }
}
