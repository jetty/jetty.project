//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.api.frames;

import java.util.Arrays;

public class NewConnectionIdFrame extends Frame.Abstract
{
    private final byte[] connectionId;
    private final long sequenceNumber;
    private final long retirePriorTo;
    private final byte[] resetToken;

    public NewConnectionIdFrame(byte[] connectionId, long sequenceNumber, long retirePriorTo, byte[] resetToken)
    {
        super(0x18);
        if (connectionId.length < 1 || connectionId.length > 20)
            throw new IllegalArgumentException("invalid_connection_id");
        this.connectionId = connectionId;
        this.sequenceNumber = sequenceNumber;
        this.retirePriorTo = retirePriorTo;
        if (resetToken.length != 16)
            throw new IllegalArgumentException("invalid_reset_token");
        this.resetToken = resetToken;
    }

    public byte[] connectionId()
    {
        return connectionId;
    }

    public long sequenceNumber()
    {
        return sequenceNumber;
    }

    public long retirePriorTo()
    {
        return retirePriorTo;
    }

    public byte[] resetToken()
    {
        return resetToken;
    }

    @Override
    public String toString()
    {
        return "%s[cid=%s]".formatted(super.toString(), Arrays.toString(connectionId));
    }
}
