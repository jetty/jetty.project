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

package org.eclipse.jetty.security.siwe.internal;

import java.security.SecureRandom;

public class EthereumUtil
{
    private static final String NONCE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private EthereumUtil()
    {
    }

    public static String createNonce()
    {
        StringBuilder builder = new StringBuilder(8);
        for (int i = 0; i < 8; i++)
        {
            int character = RANDOM.nextInt(NONCE_CHARACTERS.length());
            builder.append(NONCE_CHARACTERS.charAt(character));
        }

        return builder.toString();
    }
}
