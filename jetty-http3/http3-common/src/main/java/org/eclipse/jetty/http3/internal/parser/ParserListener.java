//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

    public default void onStreamFailure(long streamId, int error, String reason)
    {
    }

    public default void onSessionFailure(int error, String reason)
    {
    }
}
