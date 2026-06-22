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

package org.eclipse.jetty.tls;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.HashMap;
import java.util.Map;

public enum SignatureAlgorithm
{
    // ECDSA algorithms.
    ECDSA_SECP256R1_SHA256(0X0403),
    ECDSA_SECP384R1_SHA384(0X0503),
    ECDSA_SECP521R1_SHA512(0X0603),

    // RSASSA-PSS algorithms with public key OID RSAEncryption.
    RSA_PSS_RSAE_SHA256(0X0804),
    RSA_PSS_RSAE_SHA384(0X0805),
    RSA_PSS_RSAE_SHA512(0X0806),

    // EdDSA algorithms.
    ED25519(0x0807),
    ED448(0x0808),

    RSA_PSS_PSS_SHA256(0x0809),
    RSA_PSS_PSS_SHA384(0x080A),
    RSA_PSS_PSS_SHA512(0x080B);

    private final int code;

    SignatureAlgorithm(int code)
    {
        this.code = code;
        Codes.CODES.put(code, this);
    }

    public int code()
    {
        return code;
    }

    public boolean supports(PublicKey publicKey)
    {
        return switch (this)
        {
            case RSA_PSS_RSAE_SHA256,
                 RSA_PSS_RSAE_SHA384,
                 RSA_PSS_RSAE_SHA512 -> publicKey instanceof RSAPublicKey;
            case ECDSA_SECP256R1_SHA256 ->
                publicKey instanceof ECPublicKey ecPublicKey && ecPublicKey.getParams().getCurve().getField().getFieldSize() == 256;
            case ECDSA_SECP384R1_SHA384 ->
                publicKey instanceof ECPublicKey ecPublicKey && ecPublicKey.getParams().getCurve().getField().getFieldSize() == 384;
            case ECDSA_SECP521R1_SHA512 ->
                publicKey instanceof ECPublicKey ecPublicKey && ecPublicKey.getParams().getCurve().getField().getFieldSize() == 512;
            case ED25519, ED448 ->
                publicKey instanceof EdECPublicKey edECPublicKey && edECPublicKey.getParams().getName().equalsIgnoreCase(name());
            // TODO: support RSA_PSS_PSS_SHA.
            default -> false;
        };
    }

    public byte[] sign(PrivateKey privateKey, byte[] content) throws Exception
    {
        return switch (this)
        {
            case RSA_PSS_RSAE_SHA256 -> rsaSign(privateKey, content, 256);
            case RSA_PSS_RSAE_SHA384 -> rsaSign(privateKey, content, 384);
            case RSA_PSS_RSAE_SHA512 -> rsaSign(privateKey, content, 512);
            case ECDSA_SECP256R1_SHA256 -> ecSign(privateKey, content, 256);
            case ECDSA_SECP384R1_SHA384 -> ecSign(privateKey, content, 384);
            case ECDSA_SECP521R1_SHA512 -> ecSign(privateKey, content, 512);
            case ED25519, ED448 -> edSign(privateKey, content, name());
            // TODO: support RSA_PSS_PSS_SHA.
            default -> throw new UnsupportedOperationException();
        };
    }

    private byte[] rsaSign(PrivateKey privateKey, byte[] content, int hashLength) throws Exception
    {
        Signature signature = Signature.getInstance("RSASSA-PSS");
        String hashAlgorithm = "SHA-" + hashLength;
        PSSParameterSpec parameterSpec = new PSSParameterSpec(hashAlgorithm, "MGF1", new MGF1ParameterSpec(hashAlgorithm), hashLength / 8, 1);
        signature.setParameter(parameterSpec);
        signature.initSign(privateKey);
        signature.update(content);
        return signature.sign();
    }

    private byte[] ecSign(PrivateKey privateKey, byte[] content, int hashLength) throws Exception
    {
        Signature signature = Signature.getInstance("SHA" + hashLength + "withECDSA");
        signature.initSign(privateKey);
        signature.update(content);
        return signature.sign();
    }

    private byte[] edSign(PrivateKey privateKey, byte[] content, String algorithm) throws Exception
    {
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(privateKey);
        signature.update(content);
        return signature.sign();
    }

    public boolean verify(PublicKey publicKey, byte[] content, byte[] signature) throws Exception
    {
        return switch (this)
        {
            case RSA_PSS_RSAE_SHA256 -> rsaVerify(publicKey, content, signature, 256);
            case RSA_PSS_RSAE_SHA384 -> rsaVerify(publicKey, content, signature, 384);
            case RSA_PSS_RSAE_SHA512 -> rsaVerify(publicKey, content, signature, 512);
            case ECDSA_SECP256R1_SHA256 -> ecVerify(publicKey, content, signature, 256);
            case ECDSA_SECP384R1_SHA384 -> ecVerify(publicKey, content, signature, 384);
            case ECDSA_SECP521R1_SHA512 -> ecVerify(publicKey, content, signature, 512);
            case ED25519, ED448 -> edVerify(publicKey, content, signature, name());
            // TODO: support RSA_PSS_PSS_SHA.
            default -> throw new UnsupportedOperationException();
        };
    }

    private boolean rsaVerify(PublicKey publicKey, byte[] content, byte[] signatureBytes, int hashLength) throws Exception
    {
        Signature signature = Signature.getInstance("RSASSA-PSS");
        String hashAlgorithm = "SHA-" + hashLength;
        PSSParameterSpec parameterSpec = new PSSParameterSpec(hashAlgorithm, "MGF1", new MGF1ParameterSpec(hashAlgorithm), hashLength / 8, 1);
        signature.setParameter(parameterSpec);
        signature.initVerify(publicKey);
        signature.update(content);
        return signature.verify(signatureBytes);
    }

    private boolean ecVerify(PublicKey publicKey, byte[] content, byte[] signatureBytes, int hashLength) throws Exception
    {
        Signature signature = Signature.getInstance("SHA" + hashLength + "withECDSA");
        signature.initVerify(publicKey);
        signature.update(content);
        return signature.verify(signatureBytes);
    }

    private boolean edVerify(PublicKey publicKey, byte[] content, byte[] signatureBytes, String algorithm) throws Exception
    {
        Signature signature = Signature.getInstance(algorithm);
        signature.initVerify(publicKey);
        signature.update(content);
        return signature.verify(signatureBytes);
    }

    public static SignatureAlgorithm from(int code)
    {
        return Codes.CODES.get(code);
    }

    private static class Codes
    {
        private static final Map<Integer, SignatureAlgorithm> CODES = new HashMap<>();
    }
}
