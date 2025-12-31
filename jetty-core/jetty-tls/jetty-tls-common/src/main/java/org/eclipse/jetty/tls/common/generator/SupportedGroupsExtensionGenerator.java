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

package org.eclipse.jetty.tls.common.generator;

import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.SupportedGroupsExtension;

public class SupportedGroupsExtensionGenerator implements ExtensionGenerator
{
    @Override
    public int type()
    {
        return SupportedGroupsExtension.CODE;
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Extension extension)
    {
        return generate(accumulator, (SupportedGroupsExtension)extension);
    }

    private int generate(RetainableByteBuffer.Mutable accumulator, SupportedGroupsExtension extension)
    {
        accumulator.putShort((short)extension.code());
        List<NamedGroup> groups = extension.namedGroups();
        int listLength = 2 * groups.size();
        int totalLength = 2 + listLength;
        accumulator.putShort((short)totalLength);
        accumulator.putShort((short)listLength);
        for (NamedGroup group : groups)
        {
            accumulator.putShort((short)group.code());
        }
        return 2 + totalLength;
    }
}
