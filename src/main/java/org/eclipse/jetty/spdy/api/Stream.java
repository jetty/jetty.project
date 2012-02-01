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

public interface Stream
{
    public int getId();

    public short getVersion();

    public byte getPriority();

    public Session getSession();

    public void reply(ReplyInfo replyInfo);

    public void data(DataInfo dataInfo);

    public void headers(HeadersInfo headersInfo);

    public boolean isClosed();

    public boolean isHalfClosed();

    public interface FrameListener extends EventListener
    {
        public void onReply(Stream stream, ReplyInfo replyInfo);

        public void onHeaders(Stream stream, HeadersInfo headersInfo);

        public void onData(Stream stream, DataInfo dataInfo);

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
