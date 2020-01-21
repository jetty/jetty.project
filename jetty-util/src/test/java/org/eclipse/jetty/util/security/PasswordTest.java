//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PasswordTest
{
    @Test
    public void testDeobfuscate()
    {
        // check any changes do not break already encoded strings
        String password = "secret password !# ";
        String obfuscate = "OBF:1iaa1g3l1fb51i351sw01ym91hdc1yt41v1p1ym71v2p1yti1hhq1ym51svy1hyl1f7h1fzx1i5o";
        assertEquals(password, Password.deobfuscate(obfuscate));
    }

    @Test
    public void testObfuscate()
    {
        String password = "secret password !# ";
        String obfuscate = Password.obfuscate(password);
        assertEquals(password, Password.deobfuscate(obfuscate));
    }

    @Test
    public void testObfuscateUnicode()
    {
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        String password = "secret password !#\u20ac ";
        String obfuscate = Password.obfuscate(password);
        assertEquals(password, Password.deobfuscate(obfuscate));
    }
}
