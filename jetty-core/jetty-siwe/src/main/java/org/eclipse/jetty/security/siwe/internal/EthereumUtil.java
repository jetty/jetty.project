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

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9IntegerConverter;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.eclipse.jetty.security.siwe.EthereumAuthenticator;
import org.eclipse.jetty.util.StringUtil;

public class EthereumUtil
{
    public static final String PREFIX = "\u0019Ethereum Signed Message:\n";
    private static final String NONCE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ADDRESS_LENGTH_BYTES = 20;
    private static final X9ECParameters SEC_P256K1_PARAMS = CustomNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN_PARAMS = new ECDomainParameters(
        SEC_P256K1_PARAMS.getCurve(), SEC_P256K1_PARAMS.getG(), SEC_P256K1_PARAMS.getN(), SEC_P256K1_PARAMS.getH());
    private static final BigInteger PRIME = SEC_P256K1_PARAMS.getCurve().getField().getCharacteristic();
    private static final X9IntegerConverter INT_CONVERTER = new X9IntegerConverter();
    private static final Charset CHARSET = StandardCharsets.UTF_8;

    private EthereumUtil()
    {
    }

    /**
     * Recover the Ethereum Address from the {@link EthereumAuthenticator.SignedMessage}.
     * <p>
     * This uses algorithms and terminology defined in <a href="https://eips.ethereum.org/EIPS/eip-191">EIP-191</a> and
     * <a href="https://en.wikipedia.org/wiki/Elliptic_Curve_Digital_Signature_Algorithm">ECDSA</a>.
     * </p>
     * @param signedMessage the signed message used to recover the address.
     * @return the ethereum address recovered from the signature.
     */
    public static String recoverAddress(EthereumAuthenticator.SignedMessage signedMessage)
    {
        String siweMessage = signedMessage.message();
        String signatureHex = signedMessage.signature();
        if (StringUtil.asciiStartsWithIgnoreCase(signatureHex, "0x"))
            signatureHex = signatureHex.substring(2);

        int messageLength = siweMessage.getBytes(CHARSET).length;
        String prefixedMessage = PREFIX + messageLength + siweMessage;
        byte[] messageHash = keccak256(prefixedMessage.getBytes(CHARSET));
        byte[] signatureBytes = StringUtil.fromHexString(signatureHex);

        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signatureBytes, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signatureBytes, 32, 64));
        byte v = (byte)(signatureBytes[64] < 27 ? signatureBytes[64] : signatureBytes[64] - 27);

        ECPoint qPoint = ecRecover(messageHash, v, r, s);
        if (qPoint == null)
            return null;
        return toAddress(qPoint);
    }

    public static ECPoint ecRecover(byte[] hash, int v, BigInteger r, BigInteger s)
    {
        if (v < 0 || v >= 4)
            throw new IllegalArgumentException("Invalid v value: " + v);

        // Verify that r and s are integers in [1, n-1]. If not, the signature is invalid.
        BigInteger n = DOMAIN_PARAMS.getN();
        if (r.compareTo(BigInteger.ONE) < 0 || r.compareTo(n.subtract(BigInteger.ONE)) > 0)
            return null;
        if (s.compareTo(BigInteger.ONE) < 0 || s.compareTo(n.subtract(BigInteger.ONE)) > 0)
            return null;

        // Calculate the curve point R.
        BigInteger x = r.add(BigInteger.valueOf(v / 2).multiply(n));
        if (x.compareTo(PRIME) >= 0)
            return null;
        ECPoint rPoint = decodePoint(x, v);
        if (!rPoint.multiply(n).isInfinity())
            return null;

        // Calculate the curve point Q = u1 * G + u2 * R, where u1=-zr^(-1)%n and u2=sr^(-1)%n.
        // Note: for secp256k1 z=e as the hash is 256 bits and z is defined as the Ln leftmost bits of e.
        BigInteger e = new BigInteger(1, hash);
        BigInteger rInv = r.modInverse(n);
        BigInteger u1 = e.negate().multiply(rInv).mod(n);
        BigInteger u2 = s.multiply(rInv).mod(n);
        return ECAlgorithms.sumOfTwoMultiplies(DOMAIN_PARAMS.getG(), u1, rPoint, u2);
    }

    public static String toAddress(ECPoint point)
    {
        // Remove the 1-byte prefix and return the public key as an ethereum address.
        byte[] qBytes = point.getEncoded(false);
        byte[] qHash = keccak256(qBytes, 1, qBytes.length - 1);
        byte[] address = new byte[ADDRESS_LENGTH_BYTES];
        System.arraycopy(qHash, qHash.length - ADDRESS_LENGTH_BYTES, address, 0, ADDRESS_LENGTH_BYTES);
        return "0x" + StringUtil.toHexString(address);
    }

    public static ECPoint decodePoint(BigInteger p, int v)
    {
        byte[] encodedPoint = INT_CONVERTER.integerToBytes(p, 1 + INT_CONVERTER.getByteLength(DOMAIN_PARAMS.getCurve()));
        encodedPoint[0] = (byte)((v % 2) == 0 ? 0x02 : 0x03);
        return DOMAIN_PARAMS.getCurve().decodePoint(encodedPoint);
    }

    public static byte[] keccak256(byte[] bytes)
    {
        Keccak.Digest256 digest256 = new Keccak.Digest256();
        return digest256.digest(bytes);
    }

    public static byte[] keccak256(byte[] buf, int offset, int len)
    {
        Keccak.Digest256 digest256 = new Keccak.Digest256();
        digest256.update(buf, offset, len);
        return digest256.digest();
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
