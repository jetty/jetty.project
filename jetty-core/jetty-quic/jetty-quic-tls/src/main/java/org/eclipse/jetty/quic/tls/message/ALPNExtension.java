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

package org.eclipse.jetty.quic.tls.message;

import java.util.List;
import org.eclipse.jetty.util.StringUtil;

public record ALPNExtension(List<String> protocols) implements Extension
{
    public static final int TYPE = 0x0010;

    public ALPNExtension
    {
        for (String protocol : protocols)
        {
            if (StringUtil.isBlank(protocol))
                throw new IllegalArgumentException("invalid protocol '%s'".formatted(protocol));
        }
    }

    @Override
    public int type()
    {
        return TYPE;
    }
}
