# 09 · 请求层 (request)

`@vben/request` 是基于 axios 的二次封装，提供拦截器、刷新令牌、上传/下载、SSE、AES 加解密能力。每个应用都需要在自己的 `api/request.ts` 中创建 `requestClient` 与 `baseRequestClient`。

## 9.1 包结构

```text
packages/effects/request/
├── src/
│   ├── index.ts                  # 对外导出
│   └── request-client/
│       ├── index.ts
│       ├── request-client.ts     # RequestClient 主类
│       ├── preset-interceptors.ts
│       ├── types.ts
│       └── modules/
│           ├── interceptor.ts    # InterceptorManager
│           ├── downloader.ts     # FileDownloader
│           ├── uploader.ts       # FileUploader
│           └── sse.ts            # SSE
└── package.json
```

## 9.2 `RequestClient` 主类

```ts
class RequestClient {
  public readonly instance: AxiosInstance;
  public isRefreshing = false;
  public refreshTokenQueue: ((token: string) => void)[] = [];
  public addRequestInterceptor: ...;
  public addResponseInterceptor: ...;
  public download: ...;
  public upload: ...;
  public postSSE: ...;
  public requestSSE: ...;

  constructor(options: RequestClientOptions = {}) {
    const defaultConfig = {
      headers: { 'Content-Type': 'application/json;charset=utf-8' },
      responseReturn: 'raw',
      timeout: 30_000,
      paramsSerializer: 'repeat',
    };
    const requestConfig = merge(options, defaultConfig);
    requestConfig.paramsSerializer = getParamsSerializer(...);
    this.instance = axios.create(requestConfig);
    bindMethods(this);
    // 初始化 InterceptorManager、FileUploader、FileDownloader、SSE
  }

  // 基础方法
  public get/post/put/delete/patch/head/options/request<T>(config): Promise<T>;

  // 上传 / 下载 / SSE
  public upload(...) {...}   // FileUploader.upload
  public download(...) {...} // FileDownloader.download
  public postSSE(...) {...}  // SSE.postSSE
  public requestSSE(...) {...}
}
```

### `RequestClientOptions`（来自 `types.ts`）

| 字段 | 说明 |
| --- | --- |
| `baseURL` | API 前缀 |
| `headers` | 默认请求头 |
| `timeout` | 超时（默认 30s） |
| `responseReturn` | `'data'` 取 `data`、`'raw'` 取整 Response、`'body'` 取 body |
| `paramsSerializer` | `'brackets'` / `'comma'` / `'indices'` / `'repeat'` / 自定义 |

## 9.3 `preset-interceptors.ts`

| 函数 | 作用 |
| --- | --- |
| `defaultResponseInterceptor({ codeField, dataField, successCode })` | 把 `response.data` 按 `{ code, data, msg }` 解包：成功返回 `data`；失败抛错 |
| `authenticateResponseInterceptor({ client, doReAuthenticate, doRefreshToken, enableRefreshToken, formatToken })` | 401 自动刷新令牌；并发请求走队列；失败则 `doReAuthenticate` |
| `errorMessageResponseInterceptor(showMessage)` | 通用错误提示；可定制提示内容 |

### `authenticateResponseInterceptor` 工作流程

1. 解析响应，识别业务 `code === 401`（HTTP 也可能是 401）。
2. 如果 `isRefreshing === false`：
   - 置 `isRefreshing = true`；
   - 调用 `doRefreshToken()` 获取新 token；成功则更新 store，并把队列里的请求全部重发；失败则 `doReAuthenticate()`。
3. 如果 `isRefreshing === true`，把当前请求 push 进 `refreshTokenQueue`，等 `resolve(newToken)` 后重新发请求。

## 9.4 `apps/web-antd/src/api/request.ts` 解析

```ts
const { apiURL } = useAppConfig(import.meta.env, import.meta.env.PROD);
const tenantEnable = isTenantEnable();
const apiEncrypt = createApiEncrypt(import.meta.env);
```

`createApiEncrypt(env)` 根据 `VITE_APP_API_ENCRYPT_*` 构造 AES 加解密器，详见 [§9.8](#98-aes-加解密)。

### `createRequestClient(baseURL, options)` 关键拦截器

```ts
client.addRequestInterceptor({
  fulfilled: (config) => {
    const accessStore = useAccessStore();
    config.headers.Authorization = formatToken(accessStore.accessToken);
    config.headers['Accept-Language'] = preferences.app.locale;
    config.headers['tenant-id'] = tenantEnable ? accessStore.tenantId : undefined;
    config.headers['visit-tenant-id'] = tenantEnable ? accessStore.visitTenantId : undefined;

    if (config.headers?.isEncrypt && config.data) {
      config.data = apiEncrypt.encryptRequest(config.data);
      config.headers[apiEncrypt.getEncryptHeader()] = 'true';
    }
    return config;
  },
});
```

> `headers.isEncrypt = true` 时才会触发请求加密；服务端解密后回包时需设置加密标识头。

响应拦截器：
1. **解密响应**：若响应头包含加密标识且 `data` 是字符串，则解密为对象。
2. **Blob JSON 兜底**：`responseType: 'blob'` 的请求如果后端返回 `{code, data}` 的 JSON Blob，统一转为可走 `defaultResponseInterceptor` 流程。
3. **`defaultResponseInterceptor`**：`{ codeField: 'code', dataField: 'data', successCode: 0 }`，`code === 0` 视为成功。
4. **`authenticateResponseInterceptor`**：401 → 刷新令牌 / 重新认证。
5. **`errorMessageResponseInterceptor`**：默认 `message.error(msg)`；`code === 401` 不重复提示（因为会跳登录）。

### `baseRequestClient`

```ts
export const baseRequestClient = new RequestClient({ baseURL: apiURL });
baseRequestClient.addRequestInterceptor({
  fulfilled: (config) => {
    const accessStore = useAccessStore();
    config.headers['tenant-id'] = tenantEnable ? accessStore.tenantId : undefined;
    config.headers['visit-tenant-id'] = tenantEnable ? accessStore.visitTenantId : undefined;
    return config;
  },
});
```

> 用途：`/system/auth/refresh-token`、`/system/auth/logout`、`/system/captcha/get`、`/system/captcha/check` 等不需要登录令牌的接口，但同样需要租户头。

## 9.5 业务 API 编写示例

```ts
// apps/web-antd/src/api/core/auth.ts
export namespace AuthApi {
  export interface LoginParams { username?: string; password?: string; captchaVerification?: string; }
  export interface LoginResult { accessToken: string; refreshToken: string; userId: number; expiresTime: number; }
}

export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<AuthApi.LoginResult>('/system/auth/login', data, {
    headers: { isEncrypt: false },   // 登录请求不加密（便于调试）
  });
}

export async function refreshTokenApi(refreshToken: string) {
  return baseRequestClient.post(`/system/auth/refresh-token?refreshToken=${refreshToken}`);
}
```

## 9.6 上传 / 下载 / SSE

```ts
await requestClient.upload({
  url: '/infra/file/upload',
  file,
  data: { bizType: 'avatar' },
  onUploadProgress: (e) => console.log(e.percent),
});

await requestClient.download({
  url: `/infra/file/${id}/download`,
  filename: 'demo.pdf',
});

await requestClient.requestSSE({
  url: '/ai/chat',
  data: { prompt: '...' },
  onMessage: (chunk) => appendMessage(chunk),
  onError: (err) => console.error(err),
});
```

### `FileUploader` / `FileDownloader` 内部细节

- 上传：把 `File` 转 `FormData`，监听 `onUploadProgress`。
- 下载：使用 `Blob` + `URL.createObjectURL`，文件名通过 `Content-Disposition: attachment;filename=...` 解析；无后端头时取响应 URL。

### SSE

- 内部用 `fetch` + `@microsoft/fetch-event-source`（catalog 中已包含）实现流式响应。
- `postSSE` / `requestSSE` 接收 `onMessage / onError / onComplete / signal`。

## 9.7 错误处理约定

- 网络异常 / 超时 → `errorMessageResponseInterceptor` → `message.error(msg)`。
- 业务码 `code !== 0`：由 `defaultResponseInterceptor` 抛错，错误对象为 `AxiosError`，可读取 `error.data` 拿到 `{ code, msg, data }`。
- 401：进入令牌刷新流程；刷新失败 → `doReAuthenticate()` → 调用 `authStore.logout()`，跳登录页。

## 9.8 AES 加解密

文件：`packages/utils/src/helpers.ts` 中的 `createApiEncrypt(env)` + `packages/@core/base/shared/src/utils/encrypt.ts`。

```ts
const apiEncrypt = createApiEncrypt(import.meta.env);
// 等价于：
{
  encryptRequest: (data) => AES.encrypt(JSON.stringify(data), requestKey, { iv, mode: 'CBC' }),
  decryptResponse: (str) => JSON.parse(AES.decrypt(str, responseKey, { iv, mode: 'CBC' }).toString(enc.Utf8)),
  getEncryptHeader: () => env.VITE_APP_API_ENCRYPT_HEADER,
}
```

环境变量：

```ini
VITE_APP_API_ENCRYPT_ENABLE=true
VITE_APP_API_ENCRYPT_HEADER=X-Api-Encrypt
VITE_APP_API_ENCRYPT_ALGORITHM=AES
VITE_APP_API_ENCRYPT_REQUEST_KEY=52549111389893486934626385991395
VITE_APP_API_ENCRYPT_RESPONSE_KEY=96103715984234343991809655248883
```

工作流程：
1. 请求：`config.headers.isEncrypt = true` → 加密 `config.data` → 写响应头 `X-Api-Encrypt: true`。
2. 响应：检查响应头 `X-Api-Encrypt === 'true'` → 解密字符串 → 进入正常拦截器链。