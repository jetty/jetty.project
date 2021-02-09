//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
