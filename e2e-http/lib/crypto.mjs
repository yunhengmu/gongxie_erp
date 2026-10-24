import crypto from 'node:crypto';

/**
 * 登录接口（`/auth/login`）的加解密，规则与前端 `code/front-end/src/utils/{request,crypto,jsencrypt}.ts` 一致：
 * 请求体 AES-256-ECB/PKCS7 加密，AES 密钥经 RSA 公钥加密后放请求头 `encrypt-key`；
 * 响应若带 `encrypt-key` 头，则用 RSA 私钥解出 AES 密钥再解密响应体。
 *
 * <p>全仓仅 AuthController / SysProfileController / SysUserController 使用 `@ApiEncrypt`，
 * 因此加解密只需要覆盖登录这一步。</p>
 */

/**
 * 生成 AES-256 密钥：32 个 hex 字符的 ASCII 字节（与前端 generateAesKey 等价）。
 *
 * @returns {Buffer} 32 字节密钥
 */
export const generateAesKey = () => Buffer.from(crypto.randomBytes(16).toString('hex'), 'utf8');

/**
 * AES-256-ECB + PKCS7 加密。
 *
 * @param {string} plain 明文
 * @param {Buffer} key 32 字节密钥
 * @returns {string} base64 密文
 */
export const aesEncrypt = (plain, key) => {
  const cipher = crypto.createCipheriv('aes-256-ecb', key, null);
  return Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]).toString('base64');
};

/**
 * AES-256-ECB + PKCS7 解密。
 *
 * @param {string} base64 密文
 * @param {Buffer} key 32 字节密钥
 * @returns {string} 明文
 */
export const aesDecrypt = (base64, key) => {
  const decipher = crypto.createDecipheriv('aes-256-ecb', key, null);
  return Buffer.concat([decipher.update(Buffer.from(base64, 'base64')), decipher.final()]).toString('utf8');
};

/**
 * RSA 公钥加密（PKCS#1 v1.5，与 JSEncrypt 默认填充一致）。
 *
 * @param {string} text 明文
 * @param {string} publicKeyBase64 SPKI 或 PKCS#1 的 base64 DER
 * @returns {string} base64 密文
 */
export const rsaEncrypt = (text, publicKeyBase64) =>
  crypto
    .publicEncrypt(
      { key: importPublicKey(publicKeyBase64), padding: crypto.constants.RSA_PKCS1_PADDING },
      Buffer.from(text, 'utf8')
    )
    .toString('base64');

/**
 * RSA 私钥解密。
 *
 * @param {string} base64 密文
 * @param {string} privateKeyBase64 PKCS#8 或 PKCS#1 的 base64 DER
 * @returns {string} 明文
 */
export const rsaDecrypt = (base64, privateKeyBase64) =>
  crypto
    .privateDecrypt(
      { key: importPrivateKey(privateKeyBase64), padding: crypto.constants.RSA_PKCS1_PADDING },
      Buffer.from(base64, 'base64')
    )
    .toString('utf8');

/**
 * 组装登录请求：返回加密后的请求体与 `encrypt-key` 头值。
 *
 * @param {object} body 登录请求体
 * @param {string} publicKeyBase64 RSA 公钥
 * @returns {{ body: string, encryptKey: string, aesKey: Buffer }} 加密结果
 */
export const encryptLoginBody = (body, publicKeyBase64) => {
  const aesKey = generateAesKey();
  const bodyText = aesEncrypt(JSON.stringify(body), aesKey);
  const encryptKey = rsaEncrypt(aesKey.toString('base64'), publicKeyBase64);
  return { body: bodyText, encryptKey, aesKey };
};

/**
 * 若响应带 `encrypt-key` 头则解密响应体，否则按明文解析。
 *
 * @param {string} text 响应体原文
 * @param {string|null} encryptKeyHeader 响应头 `encrypt-key`
 * @param {string} privateKeyBase64 RSA 私钥
 * @returns {object} 解析后的 JSON
 */
export const decryptResponse = (text, encryptKeyHeader, privateKeyBase64) => {
  if (!encryptKeyHeader) {
    return JSON.parse(text);
  }
  const aesKey = Buffer.from(rsaDecrypt(encryptKeyHeader, privateKeyBase64), 'base64');
  return JSON.parse(aesDecrypt(text, aesKey));
};

/**
 * 导入 RSA 公钥：兼容 SPKI（X.509）与 PKCS#1。
 *
 * @param {string} base64 base64 DER
 * @returns {crypto.KeyObject} 公钥
 */
const importPublicKey = (base64) => {
  const der = Buffer.from(base64, 'base64');
  for (const type of ['spki', 'pkcs1']) {
    try {
      return crypto.createPublicKey({ key: der, format: 'der', type });
    } catch {
      // 尝试下一种格式
    }
  }
  throw new Error('无法导入 RSA 公钥（既不是 SPKI 也不是 PKCS#1 的 base64 DER），请检查 VITE_APP_RSA_PUBLIC_KEY');
};

/**
 * 导入 RSA 私钥：兼容 PKCS#8 与 PKCS#1。
 *
 * @param {string} base64 base64 DER
 * @returns {crypto.KeyObject} 私钥
 */
const importPrivateKey = (base64) => {
  const der = Buffer.from(base64, 'base64');
  for (const type of ['pkcs8', 'pkcs1']) {
    try {
      return crypto.createPrivateKey({ key: der, format: 'der', type });
    } catch {
      // 尝试下一种格式
    }
  }
  throw new Error('无法导入 RSA 私钥（既不是 PKCS#8 也不是 PKCS#1 的 base64 DER），请检查 VITE_APP_RSA_PRIVATE_KEY');
};

/**
 * 对机密做打码，避免写入日志或报告。
 *
 * @param {string} secret 原始值
 * @returns {string} 打码结果
 */
export const maskSecret = (secret) => {
  if (!secret) {
    return '(未设置)';
  }
  return `${secret.slice(0, 4)}***（长度 ${secret.length}）`;
};
