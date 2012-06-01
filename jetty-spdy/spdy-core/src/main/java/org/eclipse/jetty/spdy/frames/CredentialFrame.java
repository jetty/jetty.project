/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.frames;

import java.security.cert.Certificate;

public class CredentialFrame extends ControlFrame
{
    private final short slot;
    private final byte[] proof;
    private final Certificate[] certificateChain;

    public CredentialFrame(short version, short slot, byte[] proof, Certificate[] certificateChain)
    {
        super(version, ControlFrameType.CREDENTIAL, (byte)0);
        this.slot = slot;
        this.proof = proof;
        this.certificateChain = certificateChain;
    }

    public short getSlot()
    {
        return slot;
    }

    public byte[] getProof()
    {
        return proof;
    }

    public Certificate[] getCertificateChain()
    {
        return certificateChain;
    }
}
