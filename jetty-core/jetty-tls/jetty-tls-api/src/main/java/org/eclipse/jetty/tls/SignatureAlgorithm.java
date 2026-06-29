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

/// NOTE: this class is not an enum because it has an open set of values
/// that are part of TLS messages that are used to derive cryptographic keys,
/// and can have _grease_ values of the form `0x?A?A` per RFC-8701.
public final class SignatureAlgorithm
{
    private static final Map<Integer, SignatureAlgorithm> INSTANCES = new HashMap<>();

    // ECDSA algorithms.
    public static final int ECDSA_SECP256R1_SHA256_CODE = 0x0403;
    public static final SignatureAlgorithm ECDSA_SECP256R1_SHA256 = create(ECDSA_SECP256R1_SHA256_CODE, "ECDSA_SECP256R1_SHA256");

    public static final int ECDSA_SECP384R1_SHA384_CODE = 0x0503;
    public static final SignatureAlgorithm ECDSA_SECP384R1_SHA384 = create(ECDSA_SECP384R1_SHA384_CODE, "ECDSA_SECP384R1_SHA384");

    public static final int ECDSA_SECP521R1_SHA512_CODE = 0x0603;
    public static final SignatureAlgorithm ECDSA_SECP521R1_SHA512 = create(ECDSA_SECP521R1_SHA512_CODE, "ECDSA_SECP521R1_SHA512");

    // RSASSA-PSS algorithms with public key OID RSAEncryption.
    public static final int RSA_PSS_RSAE_SHA256_CODE = 0x0804;
    public static final SignatureAlgorithm RSA_PSS_RSAE_SHA256 = create(RSA_PSS_RSAE_SHA256_CODE, "RSA_PSS_RSAE_SHA256");

    public static final int RSA_PSS_RSAE_SHA384_CODE = 0x0805;
    public static final SignatureAlgorithm RSA_PSS_RSAE_SHA384 = create(RSA_PSS_RSAE_SHA384_CODE, "RSA_PSS_RSAE_SHA384");

    public static final int RSA_PSS_RSAE_SHA512_CODE = 0x0806;
    public static final SignatureAlgorithm RSA_PSS_RSAE_SHA512 = create(RSA_PSS_RSAE_SHA512_CODE, "RSA_PSS_RSAE_SHA512");

    // EdDSA algorithms.
    public static final int ED25519_CODE = 0x0807;
    public static final SignatureAlgorithm ED25519 = create(ED25519_CODE, "ED25519");

    public static final int ED448_CODE = 0x0808;
    public static final SignatureAlgorithm ED448 = create(ED448_CODE, "ED448");

    // RSA-PSS-PSS algorithms.
    public static final int RSA_PSS_PSS_SHA256_CODE = 0x0809;
    public static final SignatureAlgorithm RSA_PSS_PSS_SHA256 = create(RSA_PSS_PSS_SHA256_CODE, "RSA_PSS_PSS_SHA256");

    public static final int RSA_PSS_PSS_SHA384_CODE = 0x080A;
    public static final SignatureAlgorithm RSA_PSS_PSS_SHA384 = create(RSA_PSS_PSS_SHA384_CODE, "RSA_PSS_PSS_SHA384");

    public static final int RSA_PSS_PSS_SHA512_CODE = 0x080B;
    public static final SignatureAlgorithm RSA_PSS_PSS_SHA512 = create(RSA_PSS_PSS_SHA512_CODE, "RSA_PSS_PSS_SHA512");

    private final int code;
    private final String name;

    private SignatureAlgorithm(int code, String name)
    {
        this.code = code;
        this.name = name;
    }

    public int code()
    {
        return code;
    }

    public String name()
    {
        return name;
    }

    public boolean supports(PublicKey publicKey)
    {
        return switch (code())
        {
            case RSA_PSS_RSAE_SHA256_CODE,
                 RSA_PSS_RSAE_SHA384_CODE,
                 RSA_PSS_RSAE_SHA512_CODE -> publicKey instanceof RSAPublicKey;
            case ECDSA_SECP256R1_SHA256_CODE ->
                publicKey instanceof ECPublicKey ecPublicKey && ecPublicKey.getParams().getCurve().getField().getFieldSize() == 256;
            case ECDSA_SECP384R1_SHA384_CODE ->
                publicKey instanceof ECPublicKey ecPublicKey && ecPublicKey.getParams().getCurve().getField().getFieldSize() == 384;
            case ECDSA_SECP521R1_SHA512_CODE ->
                publicKey instanceof ECPublicKey ecPublicKey && ecPublicKey.getParams().getCurve().getField().getFieldSize() == 512;
            case ED25519_CODE, ED448_CODE ->
                publicKey instanceof EdECPublicKey edECPublicKey && edECPublicKey.getParams().getName().equalsIgnoreCase(name());
            // TODO: support RSA_PSS_PSS_SHA.
            default -> false;
        };
    }

    public byte[] sign(PrivateKey privateKey, byte[] content) throws Exception
    {
        return switch (code())
        {
            case RSA_PSS_RSAE_SHA256_CODE -> rsaSign(privateKey, content, 256);
            case RSA_PSS_RSAE_SHA384_CODE -> rsaSign(privateKey, content, 384);
            case RSA_PSS_RSAE_SHA512_CODE -> rsaSign(privateKey, content, 512);
            case ECDSA_SECP256R1_SHA256_CODE -> ecSign(privateKey, content, 256);
            case ECDSA_SECP384R1_SHA384_CODE -> ecSign(privateKey, content, 384);
            case ECDSA_SECP521R1_SHA512_CODE -> ecSign(privateKey, content, 512);
            case ED25519_CODE, ED448_CODE -> edSign(privateKey, content, name());
            // TODO: support RSA_PSS_PSS_SHA.
            default -> throw new UnsupportedOperationException("unknown " + this);
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
        return switch (code())
        {
            case RSA_PSS_RSAE_SHA256_CODE -> rsaVerify(publicKey, content, signature, 256);
            case RSA_PSS_RSAE_SHA384_CODE -> rsaVerify(publicKey, content, signature, 384);
            case RSA_PSS_RSAE_SHA512_CODE -> rsaVerify(publicKey, content, signature, 512);
            case ECDSA_SECP256R1_SHA256_CODE -> ecVerify(publicKey, content, signature, 256);
            case ECDSA_SECP384R1_SHA384_CODE -> ecVerify(publicKey, content, signature, 384);
            case ECDSA_SECP521R1_SHA512_CODE -> ecVerify(publicKey, content, signature, 512);
            case ED25519_CODE, ED448_CODE -> edVerify(publicKey, content, signature, name());
            // TODO: support RSA_PSS_PSS_SHA.
            default -> throw new UnsupportedOperationException("unknown " + this);
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

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj instanceof SignatureAlgorithm that)
            return code == that.code;
        return false;
    }

    @Override
    public int hashCode()
    {
        return Integer.hashCode(code);
    }

    @Override
    public String toString()
    {
        return "%s[%s]".formatted(getClass().getSimpleName(), name());
    }

    public static SignatureAlgorithm from(int code)
    {
        SignatureAlgorithm result = INSTANCES.get(code);
        return result != null ? result : new SignatureAlgorithm(code, "0x%x".formatted(code));
    }

    private static SignatureAlgorithm create(int code, String name)
    {
        return INSTANCES.computeIfAbsent(code, c -> new SignatureAlgorithm(c, name));
    }
}
