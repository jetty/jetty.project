//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal.parser;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;

public interface ParserListener
{
    public default void onHeaders(long streamId, HeadersFrame frame)
    {
    }

    public default void onData(long streamId, DataFrame frame)
    {
    }

    public default void onSettings(SettingsFrame frame)
    {
    }

    public default void onGoAway(GoAwayFrame frame)
    {
    }

    public default void onStreamFailure(long streamId, long error, Throwable failure)
    {
    }

    public default void onSessionFailure(long error, String reason, Throwable failure)
    {
    }

    public static class Wrapper implements ParserListener
    {
        protected final ParserListener listener;

        public Wrapper(ParserListener listener)
        {
            this.listener = listener;
        }

        @Override
        public void onHeaders(long streamId, HeadersFrame frame)
        {
            listener.onHeaders(streamId, frame);
        }

        @Override
        public void onData(long streamId, DataFrame frame)
        {
            listener.onData(streamId, frame);
        }

        @Override
        public void onSettings(SettingsFrame frame)
        {
            listener.onSettings(frame);
        }

        @Override
        public void onStreamFailure(long streamId, long error, Throwable failure)
        {
            listener.onStreamFailure(streamId, error, failure);
        }

        @Override
        public void onSessionFailure(long error, String reason, Throwable failure)
        {
            listener.onSessionFailure(error, reason, failure);
        }
    }
}
