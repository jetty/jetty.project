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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link SessionFrameListener} is the passive counterpart of a {@link Session} and receives events happening
 * on a SPDY session.</p>
 *
 * @see Session
 */
public interface SessionFrameListener extends EventListener
{
    /**
     * <p>Callback invoked when a request to create a stream has been received.</p>
     * <p>Application code should implement this method and reply to the stream creation, eventually
     * sending data:</p>
     * <pre>
     * public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
     * {
     *     // Do something with the metadata contained in synInfo
     *
     *     if (stream.isHalfClosed()) // The other peer will not send data
     *     {
     *         stream.reply(new ReplyInfo(false));
     *         stream.data(new StringDataInfo("foo", true));
     *         return null; // Not interested in further stream events
     *     }
     *
     *     ...
     * }
     * </pre>
     * <p>Alternatively, if the stream creation requires reading data sent from the other peer:</p>
     * <pre>
     * public Stream.FrameListener onSyn(Stream stream, SynInfo synInfo)
     * {
     *     // Do something with the metadata contained in synInfo
     *
     *     if (!stream.isHalfClosed()) // The other peer will send data
     *     {
     *         stream.reply(new ReplyInfo(true));
     *         return new Stream.FrameListener.Adapter() // Interested in stream events
     *         {
     *             public void onData(Stream stream, DataInfo dataInfo)
     *             {
     *                 // Do something with the incoming data in dataInfo
     *             }
     *         };
     *     }
     *
     *     ...
     * }
     * </pre>
     *
     * @param stream  the stream just created
     * @param synInfo the metadata sent on stream creation
     * @return a listener for stream events, or null if there is no interest in being notified of stream events
     */
    public StreamFrameListener onSyn(Stream stream, SynInfo synInfo);

    /**
     * <p>Callback invoked when a stream error happens.</p>
     *
     * @param session the session
     * @param rstInfo the metadata of the stream error
     */
    public void onRst(Session session, RstInfo rstInfo);

    /**
     * <p>Callback invoked when a request to configure the SPDY connection has been received.</p>
     *
     * @param session the session
     * @param settingsInfo the metadata sent to configure
     */
    public void onSettings(Session session, SettingsInfo settingsInfo);

    /**
     * <p>Callback invoked when a ping request has completed its round-trip.</p>
     *
     * @param session the session
     * @param pingInfo the metadata received
     */
    public void onPing(Session session, PingInfo pingInfo);

    /**
     * <p>Callback invoked when the other peer signals that it is closing the connection.</p>
     *
     * @param session the session
     * @param goAwayInfo the metadata sent
     */
    public void onGoAway(Session session, GoAwayInfo goAwayInfo);

    /**
     * <p>Callback invoked when an exception is thrown during the processing of an event on a
     * SPDY session.</p>
     * <p>Examples of such conditions are invalid frames received, corrupted headers compression state, etc.</p>
     *
     * @param x the exception that caused the event processing failure
     */
    public void onException(Throwable x);

    /**
     * <p>Empty implementation of {@link SessionFrameListener}</p>
     */
    public static class Adapter implements SessionFrameListener
    {
        private static final Logger logger = LoggerFactory.getLogger(Adapter.class);

        @Override
        public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
        {
            return null;
        }

        @Override
        public void onRst(Session session, RstInfo rstInfo)
        {
        }

        @Override
        public void onSettings(Session session, SettingsInfo settingsInfo)
        {
        }

        @Override
        public void onPing(Session session, PingInfo pingInfo)
        {
        }

        @Override
        public void onGoAway(Session session, GoAwayInfo goAwayInfo)
        {
        }

        @Override
        public void onException(Throwable x)
        {
            logger.info("", x);
        }
    }
}
