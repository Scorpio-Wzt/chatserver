package com.zzw.chatserver.utils;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA加密工具类，用于消息签名与验证
 */
public class RSAUtil {

    // 密钥长度
    private static final int KEY_SIZE = 2048;
    // 签名算法
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    // 加密算法
    private static final String KEY_ALGORITHM = "RSA";

    /**
     * 生成RSA密钥对（公钥+私钥）
     */
    public static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        keyPairGenerator.initialize(KEY_SIZE);
        return keyPairGenerator.generateKeyPair();
    }

    /**
     * 用私钥对内容进行签名
     * @param content 待签名内容
     * @param privateKey 私钥（Base64编码字符串）
     * @return 签名结果（Base64编码）
     */
    public static String sign(String content, String privateKey) throws Exception {
        // 解码私钥
        byte[] keyBytes = Base64.getDecoder().decode(privateKey);
        PKCS8EncodedKeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        PrivateKey priKey = keyFactory.generatePrivate(pkcs8KeySpec);

        // 签名
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(priKey);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    /**
     * 用公钥验证签名
     * @param content 原始内容
     * @param publicKey 公钥（Base64编码字符串）
     * @param sign 待验证的签名（Base64编码）
     * @return 验证结果（true=通过，false=失败）
     */
    public static boolean verify(String content, String publicKey, String sign) throws Exception {
        // 解码公钥
        byte[] keyBytes = Base64.getDecoder().decode(publicKey);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
        PublicKey pubKey = keyFactory.generatePublic(keySpec);

        // 验证签名
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initVerify(pubKey);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.getDecoder().decode(sign));
    }

    /**
     * 私钥转Base64字符串
     */
    public static String privateKeyToBase64(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /**
     * 公钥转Base64字符串
     */
    public static String publicKeyToBase64(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}