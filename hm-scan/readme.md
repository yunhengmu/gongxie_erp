# hm-scan

对标 `uni.scanCode` 的扫码模块，**额外支持码制白名单（`filters`）与码制尝试顺序（`order`）**。

解决的问题：App 端内核会把 **CODE128** 误判成 **CODE_25 / UPC_E**（两者都是纯数字码制），
导致扫出来的结果变成一串数字而不是原始内容。通过 `filters: ['code128']` 约束内核只按 CODE128 解析，
从根上消除误判。

## 快速开始

```ts
import { hmScan, hmScanCode128 } from '@/uni_modules/hm-scan/js_sdk/index'

// 方式一：显式声明码制
hmScan({
  filters: ['code128'],
  success: (res) => {
    console.log(res.result)   // 条码内容
    console.log(res.scanType) // 内核判定的码制（统一写法，如 'code128'）
  },
  fail: (err) => console.warn(err.errMsg),
})

// 方式二：便捷方法（等价于 filters: ['code128']）
hmScanCode128({
  success: res => console.log(res.result),
})

// 方式三：多码制 + 指定尝试顺序
// filters 是「白名单」，order 是「尝试优先级」，两者数组顺序都有效
hmScan({
  filters: ['code128', 'code39', 'ean13', 'qr'], // 允许这 4 种
  order: ['code128', 'code39', 'ean13', 'qr'],   // 优先试 code128
  success: res => console.log(res.result, res.scanType),
})

// 方式四：只给 filters，不传 order —— 尝试顺序即 filters 的数组顺序
hmScan({
  filters: ['ean13', 'code128'], // → 先试 ean13，再试 code128
  success: res => console.log(res.scanType),
})
```

### filters 与 order 的区别

| 参数 | 作用 | 不传时 |
|---|---|---|
| `filters` | **白名单**，只识别列出的码制 | 全部码制都允许（等同 `uni.scanCode`） |
| `order` | **尝试顺序**，越靠前越优先 | 按 `filters` 的数组顺序；两者都没传则用内核默认顺序 |

> **`order` 只有 App 端真正生效。** H5 的 html5-qrcode 只有「码制集合」（无序），
> 小程序 `uni.scanCode` 更是连具体码制都指定不了。
> 若 `filters` 里只有一种码制，`order` 传不传都一样（没有第二选择）。

## API

与 `uni.scanCode` 对齐，差异只有 `filters` / `order` 两项。

| 参数 | 类型 | 说明 |
|---|---|---|
| `onlyFromCamera` | `boolean` | 是否只用相机（`false` 时可从相册选图）。**注：App 端当前未实现相册识别，此参数仅小程序端生效** |
| `keep` | `boolean` | 是否保留扫码界面（仅小程序端有效） |
| `success` | `(res) => void` | 成功回调，`res` 见下表 |
| `fail` | `(err) => void` | 失败 / 取消回调 |
| `complete` | `() => void` | 无论成败都会执行 |
| **`filters`** | `HmScanFormat[]` | **【新增】码制白名单，只识别列出的码制；不传 = 全开** |
| **`order`** | `HmScanFormat[]` | **【新增】码制尝试顺序，越靠前越优先；不传 = 内核默认顺序** |

`success` 回调的 `res`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `result` | `string` | 条码内容 |
| `scanType` | `string` | 内核判定的码制（统一小写写法） |
| `charSet` | `string` | 字符集，取不到时为空串 |
| `code` | `string?` | 原始扫码数据，部分端才有 |

### 支持的码制

`code128` `code39` `code93` `codabar` `ean13` `ean8` `upca` `upce` `itf` `datamatrix` `pdf417` `qr`

## 平台支持

| 平台 | 实现 | `filters` | `order` |
|---|---|---|---|
| App（app-vue / app-nvue） | `plus.barcode` | ✅ 生效 | ✅ 生效 |
| H5 | html5-qrcode | ✅ 生效 | ❌ 不生效 |
| 小程序 | `uni.scanCode` | ⚠️ 只能收窄到三大类 | ❌ 不生效 |

**小程序端的限制**：`uni.scanCode` 的 `scanType` 只有 `barCode` / `qrCode` / `datamatrix` 三个大类，
**无法下钻到具体码制**。传 `filters: ['code128']` 只会被翻译成 `scanType: ['barCode']`，
内核仍可能把 CODE128 误判成 CODE_25 / UPC_E。

> 若小程序端也有此问题，只能靠**结果层校验**兜底：拿到 `result` 后校验长度与字符集，
> 不符合业务约定就提示重扫。真正根治需要小程序端内核支持，当前无解。

## 目录结构

```
hm-scan/
├── package.json
├── readme.md
├── js_sdk/
│   ├── index.ts        # scan 入口 + 三端分流
│   └── type.ts         # 类型定义 + 各平台码制映射表
└── pages/
    └── hm-scan.vue     # App 端扫码页（plus.barcode 原生预览 + 自绘 UI）
```

## 接入注意

App 端扫码页需要注册到路由。本项目由 `@uni-helper/vite-plugin-uni-pages` 自动生成 `pages.json`，
所以扫码页所在目录必须出现在 `vite.config.ts` 的 `UniPages.subPackages` 白名单里：

```ts
UniPages({
  subPackages: [
    // ...
    'src/uni_modules/hm-scan',   // ← 必须有这一行，否则 navigateTo 会失败
  ],
})
```

## 打包配置（务必检查）

`plus.barcode` 依赖原生 `Barcode` 模块，与 `uni.scanCode` 是同一套依赖。参考项目已有的
`manifest.config.ts` 配置：

- `app-plus.modules.Barcode = {}`（**必须**，HBuilderX 3.6.11 起敏感模块不再默认打包）
- iOS 需要 `distribute.ios.privacyDescription.NSCameraUsageDescription`
- 相册识别还需要相册读取权限

> 标准基座包含全部模块（`pnpm dev:app` 正常），但**自定义基座 / 云打包会暴露缺失**，出问题优先查这里。

## App 端实现要点（`pages/hm-scan.vue`）

`plus.barcode` 有三个反直觉的坑，改动这个页面时务必注意：

**1. `create()` 不会把控件显示出来，必须手动 `append`**

```ts
const bc = plus.barcode.create('hmScanBarcode', ['code128'], styles)
// 不 append 就永远是黑屏 —— 这是「相机吊不起来」最常见的原因
plus.webview.currentWebview().append(bc)
bc.start()
```

`create` 的第一个参数是**扫码控件自身的标识**（给 `getBarcodeById` 用），
**不是要挂载的 DOM 节点 id**，别拿 `<view id="xxx">` 的 id 往里传。

**2. `top` / `left` / `width` / `height` 必须显式给，`position` 建议 `'absolute'`**

官方文档明确：「需要设置 styles 参数的 top/left/width/height 属性指定位置及大小，否则可能无法正确显示」。
`'static'` 时原生层会随页面滚动且**容易被绝对定位的 webview 元素盖住**，表现为「相机没起来」。

**3. 原生层自带扫码框，不要自绘遮罩去和它抢层级**

`BarcodeStyles` 提供 `frameColor` / `scanbarColor`，扫码框与扫描线由原生控件绘制。
再叠一层自绘遮罩只会造成层级混乱。

**4. 相册识别要用 `plus.barcode.scan`（模块级方法），不是实例方法**

`barcode.scan(path, ...)` 会报 `barcode.scan is not a function` —— 实例（`Barcode` 对象）上
只有 `start` / `cancel` / `close` / `setFlash` / `setStyle`，**没有 `scan`**。
`scan` 是 `plus.barcode` 模块的静态方法：`plus.barcode.scan(path, onSuccess, onFail, filters)`。
当前版本已移除相册入口，暂不需要。

### `<script setup>` 顶层禁忌

```
<script setup> 顶层严禁访问 uni.* / plus.* / getCurrentPages()
```

顶层代码在**模块初始化阶段**执行，此时 App 端运行时尚未注入这些全局对象，
会抛 `uni is not defined` 并导致**整包白屏**（`__uniappview.html` 打不开、页面退不出去）。
所有运行时 API 放进 `onLoad` / `onReady` / `onUnload`；查询参数用 `onLoad(options)` 取。

### `onBackPress` 禁忌

**`onBackPress` 里绝对不要调用 `uni.navigateBack`。**
`navigateBack` 会再次触发 `onBackPress` → 无限递归 →
`RangeError: Maximum call stack size exceeded` 并卡死页面。

正确做法：只回传结果，然后 `return false` 由运行时按默认行为返回上一页。

## 与现有 H5 扫码体系的关系

`src/utils/scan/` 那套是 H5 端摄像头引擎的选型体系（`html5-qrcode` / `zxing` / `jsqr`），
本模块**不替代它**，两者独立共存：

- **App 端** → 用 `hm-scan`（本模块，可约束码制）
- **H5 端** → 继续用 `src/utils/scan/` 的 `createH5Scanner`

后续如需统一，可在 `src/utils/scan/` 里接一层转发，当前不做强耦合。
