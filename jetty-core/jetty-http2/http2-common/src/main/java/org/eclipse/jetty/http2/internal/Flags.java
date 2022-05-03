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

package org.eclipse.jetty.http2.internal;

public interface Flags
{
    public static final int NONE = 0x00;
    public static final int END_STREAM = 0x01;
    public static final int ACK = 0x01;
    public static final int END_HEADERS = 0x04;
    public static final int PADDING = 0x08;
    public static final int PRIORITY = 0x20;
}
