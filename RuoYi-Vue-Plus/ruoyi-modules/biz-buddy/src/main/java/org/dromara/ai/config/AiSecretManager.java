package org.dromara.ai.config;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.encrypt.utils.EncryptUtils;
import org.springframework.stereotype.Component;

/**
 * 模型密钥密文管理器。
 *
 * <p>模型密钥统一以 AES 密文落库，页面与接口均不回显明文；仅在实际发起模型调用时解密。</p>
 *
 * @author BizBuddy
 */
@Component
@RequiredArgsConstructor
public class AiSecretManager {

    private final AiProperties aiProperties;

    /**
     * 加密明文密钥。
     *
     * @param plainText 明文密钥，为空时返回 null（表示未配置密钥，如本地 Ollama）
     * @return 密文
     */
    public String encrypt(String plainText) {
        if (StringUtils.isBlank(plainText)) {
            return null;
        }
        return EncryptUtils.encryptByAes(plainText, aiProperties.getCryptoKey());
    }

    /**
     * 解密密文密钥。
     *
     * @param cipherText 密文密钥
     * @return 明文密钥，密文为空时返回 null
     */
    public String decrypt(String cipherText) {
        if (StringUtils.isBlank(cipherText)) {
            return null;
        }
        return EncryptUtils.decryptByAes(cipherText, aiProperties.getCryptoKey());
    }

}