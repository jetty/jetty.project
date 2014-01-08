//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.frames;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.cert.Certificate;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.Assert;
import org.junit.Test;

public class CredentialGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        short slot = 1;
        byte[] proof = new byte[]{0, 1, 2};
        Certificate[] temp = loadCertificates();
        Certificate[] certificates = new Certificate[temp.length * 2];
        System.arraycopy(temp, 0, certificates, 0, temp.length);
        System.arraycopy(temp, 0, certificates, temp.length, temp.length);
        CredentialFrame frame1 = new CredentialFrame(SPDY.V3, slot, proof, certificates);
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        parser.parse(buffer);
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.CREDENTIAL, frame2.getType());
        CredentialFrame credential = (CredentialFrame)frame2;
        Assert.assertEquals(SPDY.V3, credential.getVersion());
        Assert.assertEquals(0, credential.getFlags());
        Assert.assertEquals(slot, credential.getSlot());
        Assert.assertArrayEquals(proof, credential.getProof());
        Assert.assertArrayEquals(certificates, credential.getCertificateChain());
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        short slot = 1;
        byte[] proof = new byte[]{0, 1, 2};
        Certificate[] certificates = loadCertificates();
        CredentialFrame frame1 = new CredentialFrame(SPDY.V3, slot, proof, certificates);
        Generator generator = new Generator(new MappedByteBufferPool(), new StandardCompressionFactory().newCompressor());
        ByteBuffer buffer = generator.control(frame1);

        Assert.assertNotNull(buffer);

        TestSPDYParserListener listener = new TestSPDYParserListener();
        Parser parser = new Parser(new StandardCompressionFactory().newDecompressor());
        parser.addListener(listener);
        while (buffer.hasRemaining())
            parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
        ControlFrame frame2 = listener.getControlFrame();

        Assert.assertNotNull(frame2);
        Assert.assertEquals(ControlFrameType.CREDENTIAL, frame2.getType());
        CredentialFrame credential = (CredentialFrame)frame2;
        Assert.assertEquals(SPDY.V3, credential.getVersion());
        Assert.assertEquals(0, credential.getFlags());
        Assert.assertEquals(slot, credential.getSlot());
        Assert.assertArrayEquals(proof, credential.getProof());
        Assert.assertArrayEquals(certificates, credential.getCertificateChain());
    }

    private Certificate[] loadCertificates() throws Exception
    {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        InputStream keyStoreStream = Resource.newResource("src/test/resources/keystore.jks").getInputStream();
        keyStore.load(keyStoreStream, "storepwd".toCharArray());
        return keyStore.getCertificateChain("mykey");
    }
}
