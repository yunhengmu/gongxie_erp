import fs from 'node:fs';
import path from 'node:path';
import url from 'node:url';

/**
 * 配置解析：环境变量 > `e2e/.env.local` > 默认值；
 * RSA 密钥对缺省时回退读前端 `plus-ui-Vue/.env.development`（该密钥对前端已公开，非机密）。
 */

const here = path.dirname(url.fileURLToPath(import.meta.url));
const e2eDir = path.resolve(here, '..');
const repoRoot = path.resolve(e2eDir, '..');

/**
 * 解析 .env 风格文件（兼容 `KEY = 'v'` 的写法与引号）。
 *
 * @param {string} file 文件路径
 * @returns {Record<string, string>} 键值对
 */
export const parseEnvFile = (file) => {
  const out = {};
  if (!fs.existsSync(file)) {
    return out;
  }
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const matched = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!matched) {
      continue;
    }
    let value = matched[2];
    if (value.length >= 2 && ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith('"') && value.endsWith('"')))) {
      value = value.slice(1, -1);
    }
    out[matched[1]] = value;
  }
  return out;
};

/**
 * 载入最终配置。
 *
 * @param {{ baseUrl?: string }} cli 命令行覆盖项
 * @returns {object} 配置
 */
export const loadConfig = (cli = {}) => {
  const frontendEnv = parseEnvFile(path.join(repoRoot, 'plus-ui-Vue', '.env.development'));
  const localEnv = parseEnvFile(path.join(e2eDir, '.env.local'));

  const pick = (key, fallback = '') => process.env[key] ?? localEnv[key] ?? fallback;
  const trimmedOr = (value, fallback) => (value && String(value).trim() ? String(value).trim() : fallback);

  return {
    e2eDir,
    repoRoot,
    baseUrl: trimmedOr(cli.baseUrl || pick('BIZBUDDY_BASE_URL'), 'http://localhost:8080').replace(/\/+$/, ''),
    username: trimmedOr(pick('BIZBUDDY_USERNAME'), 'admin'),
    password: pick('BIZBUDDY_PASSWORD') || 'admin123',
    clientId: trimmedOr(pick('BIZBUDDY_CLIENT_ID'), frontendEnv.VITE_APP_CLIENT_ID),
    rsaPublicKey: trimmedOr(pick('BIZBUDDY_RSA_PUBLIC_KEY'), frontendEnv.VITE_APP_RSA_PUBLIC_KEY),
    rsaPrivateKey: trimmedOr(pick('BIZBUDDY_RSA_PRIVATE_KEY'), frontendEnv.VITE_APP_RSA_PRIVATE_KEY),
    llmApiKey: pick('BIZBUDDY_LLM_API_KEY'),
    llmBaseUrl: pick('BIZBUDDY_LLM_BASE_URL'),
    chatModelName: trimmedOr(pick('BIZBUDDY_CHAT_MODEL_NAME'), 'deepseek-v4-pro-0813'),
    embeddingModelName: trimmedOr(pick('BIZBUDDY_EMBEDDING_MODEL_NAME'), 'qwen3.7-text-embedding-flash'),
    demoKbCode: trimmedOr(pick('BIZBUDDY_DEMO_KB_CODE'), 'bizbuddy_demo_kb'),
    demoDocName: trimmedOr(pick('BIZBUDDY_DEMO_DOC_NAME'), 'BizBuddy简介.txt'),
    demoExpertCode: trimmedOr(pick('BIZBUDDY_DEMO_EXPERT_CODE'), 'bizbuddy_intro_expert'),
    noticeExpertCode: trimmedOr(pick('BIZBUDDY_NOTICE_EXPERT_CODE'), 'notice_admin_expert')
  };
};
