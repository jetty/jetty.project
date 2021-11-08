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

package org.eclipse.jetty.quic.quiche;

interface Quiche
{
    // The current QUIC wire version.
    int QUICHE_PROTOCOL_VERSION = 0x00000001;
    // The maximum length of a connection ID.
    int QUICHE_MAX_CONN_ID_LEN = 20;

    interface quiche_cc_algorithm
    {
        int QUICHE_CC_RENO = 0,
            QUICHE_CC_CUBIC = 1;
    }
}
