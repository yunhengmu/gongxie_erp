/**
 * e2e-ui 配置解析。
 *
 * 原则与 `../e2e/lib/env.mjs` 一致：**凭证只有一个来源**——复用 `../e2e/.env.local`
 * （该文件已 gitignore），默认值与后端 e2e 相同（超管 admin / admin123）。
 * 与后端 e2e 的唯一差别是「访问地址」：后端直连 `:8080`，前端要经 **nginx**。
 *
 * 可用环境变量覆盖：`BIZBUDDY_UI_BASE_URL` / `BIZBUDDY_USERNAME` / `BIZBUDDY_PASSWORD`。
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** e2e-ui 目录（本文件在 lib/ 下） */
export const E2E_UI_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** 仓库根（biz-buddy/） */
export const REPO_ROOT = path.resolve(E2E_UI_DIR, '..');

/** 构建产物目录（截图 / trace / 报告） */
export const ARTIFACTS_DIR = path.join(E2E_UI_DIR, '.artifacts');

/** 登录态文件（由 auth setup 产出、后续用例复用） */
export const STATE_FILE = path.join(ARTIFACTS_DIR, 'auth-state.json');

/** 栈不可达标记文件（由 globalSetup 写入，各用例据此 SKIP） */
const STACK_DOWN_FLAG = path.join(ARTIFACTS_DIR, 'stack-down.flag');

const DEFAULT_UI_BASE_URL = 'http://localhost';
const DEFAULT_USERNAME = 'admin';
const DEFAULT_PASSWORD = 'admin123';
/** 默认客户端 id：与 `plus-ui-Vue/.env.production` 的 `VITE_APP_CLIENT_ID` 一致（已公开） */
const DEFAULT_CLIENT_ID = 'e5cd7e4891bf95d1d19206ce24a7b32e';

/**
 * 极简 .env 解析：仅支持 `KEY=VALUE`，忽略空行与 `#` 注释；两侧引号会去掉。
 *
 * @param {string} file 文件路径
 * @returns {Record<string, string>} 键值对（文件不存在时为空对象）
 */
const parseEnvFile = (file) => {
  if (!fs.existsSync(file)) {
    return {};
  }
  const result = {};
  for (const rawLine of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) {
      continue;
    }
    const at = line.indexOf('=');
    if (at <= 0) {
      continue;
    }
    const key = line.slice(0, at).trim();
    let value = line.slice(at + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    result[key] = value;
  }
  return result;
};

const fileEnv = parseEnvFile(path.join(REPO_ROOT, 'e2e', '.env.local'));

/**
 * 取值优先级：进程环境变量 > ../e2e/.env.local > 默认值。
 *
 * @param {string} key 键名
 * @param {string} fallback 默认值
 * @returns {string} 取值
 */
const pick = (key, fallback) => {
  const fromProcess = process.env[key];
  if (fromProcess !== undefined && fromProcess !== '') {
    return fromProcess;
  }
  const fromFile = fileEnv[key];
  if (fromFile !== undefined && fromFile !== '') {
    return fromFile;
  }
  return fallback;
};

/** 去掉结尾斜杠，便于与 `/login` 之类的路径拼接 */
const trimTrailingSlash = (url) => url.replace(/\/+$/, '');

export const CONFIG = {
  /** 前端入口基址：PG 栈 `http://localhost`（nginx 80）／MySQL 栈 `http://localhost:81` */
  uiBaseUrl: trimTrailingSlash(pick('BIZBUDDY_UI_BASE_URL', DEFAULT_UI_BASE_URL)),
  username: pick('BIZBUDDY_USERNAME', DEFAULT_USERNAME),
  password: pick('BIZBUDDY_PASSWORD', DEFAULT_PASSWORD),
  /**
   * 客户端 id：前端**每个请求**都会带 `clientid` 头（`src/utils/request.ts:94-102`），
   * 后端按其做 Sa-Token 客户端校验——**缺这个头会被判未登录**。前端已公开、非机密。
   */
  clientId: pick('BIZBUDDY_CLIENT_ID', DEFAULT_CLIENT_ID),
  /** 栈是否可达：globalSetup 未落标记时视为可达（本机直跑单个 spec 的场景） */
  stackUp: !fs.existsSync(STACK_DOWN_FLAG),
  /** 标记文件路径（供 globalSetup 写入） */
  stackDownFlag: STACK_DOWN_FLAG
};

/**
 * 供 globalSetup / spec 复用的「栈不可达」判定文案。
 *
 * @returns {string} SKIP 理由
 */
export const stackSkipReason = () =>
  `容器栈不可达（${CONFIG.uiBaseUrl}）——整层 SKIP，非失败`;
