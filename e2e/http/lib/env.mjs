import fs from 'node:fs';
import path from 'node:path';
import url from 'node:url';
import { execFileSync } from 'node:child_process';

/**
 * 配置解析：环境变量 > `e2e/http/.env.local` > 默认值；
 * RSA 密钥对缺省时回退读前端 `code/front-end/.env.development`（该密钥对前端已公开，非机密）。
 */

const here = path.dirname(url.fileURLToPath(import.meta.url));
const e2eDir = path.resolve(here, '..');
const repoRoot = path.resolve(e2eDir, '..', '..');

/**
 * 解析本机 WSL 发行版名——用于生成「给使用者复制」的命令串。
 *
 * <p>优先级：`BIZBUDDY_WSL_DISTRO` > 自动探测（`wsl --list --verbose` 里带 `*` 的默认发行版）
 * > 占位符 `&lt;distro&gt;`。</p>
 *
 * <p><b>刻意不写死发行版名</b>：本项目的开发环境同时存在 Ubuntu-22.04 与 Ubuntu-24.04，
 * 写死会让另一台机器上照抄命令的人直接撞 `WSL_E_DISTRO_NOT_FOUND`。</p>
 *
 * @returns {string} 发行版名（探测失败时为占位符）
 */
let distroCache;
export const wslDistro = () => {
  if (distroCache !== undefined) {
    return distroCache;
  }
  const fromEnv = (process.env.BIZBUDDY_WSL_DISTRO ?? '').trim();
  if (fromEnv) {
    distroCache = fromEnv;
    return distroCache;
  }
  distroCache = '<distro>';
  if (process.platform !== 'win32') {
    return distroCache;
  }
  try {
    const raw = execFileSync('wsl', ['--list', '--verbose'], { timeout: 3000 });
    // `wsl -l -v` 在 Windows 上输出 UTF-16LE，按 utf8 解会得到夹 \0 的乱码
    const text = raw.includes(0) ? raw.toString('utf16le') : raw.toString('utf8');
    for (const line of text.split(/\r?\n/)) {
      const matched = line.match(/^\s*\*\s*(\S+)/);
      if (matched) {
        distroCache = matched[1];
        break;
      }
    }
  } catch {
    // 探测失败：保留占位符，由使用者自行替换
  }
  return distroCache;
};

/**
 * 仓库根在 WSL 内的路径（`D:\a\b` → `/mnt/d/a/b`）——同类命令必须用 WSL 视角的路径。
 *
 * @returns {string} WSL 视角的仓库根
 */
export const wslRepoPath = () => {
  if (!/^[A-Za-z]:[\\/]/.test(repoRoot)) {
    // 已经在 WSL 内运行，路径本就是 WSL 视角
    return repoRoot;
  }
  return `/mnt/${repoRoot[0].toLowerCase()}${repoRoot.slice(2).replace(/\\/g, '/')}`;
};

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
  const frontendEnv = parseEnvFile(path.join(repoRoot, 'code/front-end', '.env.development'));
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
    chatModelName: trimmedOr(pick('BIZBUDDY_CHAT_MODEL_NAME'), 'deepseek-v4.1-flash'),
    embeddingModelName: trimmedOr(pick('BIZBUDDY_EMBEDDING_MODEL_NAME'), 'qwen3.7-text-embedding-flash'),
    demoKbCode: trimmedOr(pick('BIZBUDDY_DEMO_KB_CODE'), 'bizbuddy_demo_kb'),
    demoDocName: trimmedOr(pick('BIZBUDDY_DEMO_DOC_NAME'), 'BizBuddy简介.txt'),
    demoExpertCode: trimmedOr(pick('BIZBUDDY_DEMO_EXPERT_CODE'), 'bizbuddy_intro_expert'),
    noticeExpertCode: trimmedOr(pick('BIZBUDDY_NOTICE_EXPERT_CODE'), 'notice_admin_expert')
  };
};
