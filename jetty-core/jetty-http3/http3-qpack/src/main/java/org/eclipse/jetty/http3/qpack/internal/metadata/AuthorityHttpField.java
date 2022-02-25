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

package org.eclipse.jetty.http3.qpack.internal.metadata;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http3.qpack.internal.table.StaticTable;

public class AuthorityHttpField extends HostPortHttpField
{
    public static final String AUTHORITY = StaticTable.STATIC_TABLE[1][0];

    public AuthorityHttpField(String authority)
    {
        super(HttpHeader.C_AUTHORITY, AUTHORITY, authority);
    }

    @Override
    public String toString()
    {
        return String.format("%s(preparsed h=%s p=%d)", super.toString(), getHost(), getPort());
    }
}
