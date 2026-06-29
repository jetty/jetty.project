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

package org.eclipse.jetty.tls.common;

import javax.crypto.SecretKey;

import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.NamedGroup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GroupKeyPairTest
{
    @ParameterizedTest
    @MethodSource("org.eclipse.jetty.tls.NamedGroup#values")
    public void testSharedSecret(NamedGroup group) throws Exception
    {
        GroupKeyPair clientGroupKeyPair = GroupKeyPair.from(group);
        KeyShare clientKeyShare = clientGroupKeyPair.toKeyShare();

        GroupKeyPair serverGroupKeyPair = GroupKeyPair.from(group);
        KeyShare serverKeyShare = serverGroupKeyPair.toKeyShare();

        SecretKey serverSharedSecret = serverGroupKeyPair.generateSharedSecret(clientKeyShare);
        SecretKey clientSharedSecret = clientGroupKeyPair.generateSharedSecret(serverKeyShare);

        assertEquals(serverSharedSecret, clientSharedSecret);
    }
}
