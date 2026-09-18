import type { HmScanError, HmScanFormat, HmScanOptions, HmScanResult } from './type'
import {
  APP_FILTER_VALUE,
  APP_TYPE_MAP,
  toUniScanType,
} from './type'

/**
 * hm-scan —— 对标 uni.scanCode 的扫码入口
 *
 * 与 uni.scanCode 的差异只有两点：
 *   1. filters：码制白名单（只识别列出的码制）
 *   2. order  ：码制尝试顺序（越靠前越优先）
 * 其余入参、回调形态与 uni.scanCode 完全一致。
 *
 * 平台行为：
 *   - App（app-vue / app-nvue）：走 plus.barcode（跳转 pages/hm-scan 扫码页），filters/order 真正生效
 *   - 小程序：内核不支持码制约束，降级为 uni.scanCode（filters 仅收窄 scanType 大类）
 *   - H5：⚠️ 本模块**未实现** H5，调用会立即 fail（errMsg: "H5 端请使用 H5 扫码引擎"）。
 *          H5 请改用 @/utils/scan 的 H5 引擎（html5-qrcode），如 ScanDialog 组件。
 *          （原先此处注释写“H5 走 html5-qrcode、filters 生效”，与实现不符，已更正）
 *
 * 典型用法（解决 CODE128 被误判成 CODE_25 / UPC_E）：
 *   hmScan({
 *     filters: ['code128'],
 *     success: res => console.log(res.result, res.scanType),
 *   })
 */

/** 把统一写法的 filters 转成 plus.barcode 认识的值（去重） */
export function toAppFilters(filters?: HmScanFormat[]): string[] {
  if (!filters?.length)
    return []
  const out: string[] = []
  for (const f of filters) {
    const v = APP_FILTER_VALUE[f]
    if (v && !out.includes(v))
      out.push(v)
  }
  return out
}

/** order 优先，未传则退化为 filters 的顺序 */
export function toAppOrder(order: HmScanFormat[] | undefined, filters: HmScanFormat[] | undefined): string[] {
  const source = order?.length ? order : filters
  return toAppFilters(source)
}

/** plus.barcode 回调返回的数字 type → 统一写法 */
function mapAppType(type: number): string {
  // #ifdef APP-PLUS
  const dict = (plus as any)?.barcode
  if (dict) {
    for (const [constName, key] of Object.entries(APP_TYPE_MAP)) {
      if (dict[constName] === type)
        return key
    }
  }
  // #endif
  return String(type)
}

/** 统一收口：保证 success/fail 只回调一次，且 complete 必达 */
function createSettler(options: HmScanOptions) {
  let settled = false
  return {
    success(res: HmScanResult) {
      if (settled)
        return
      settled = true
      options.success?.(res)
      options.complete?.()
    },
    fail(err: HmScanError) {
      if (settled)
        return
      settled = true
      options.fail?.(err)
      options.complete?.()
    },
  }
}

/** APP 端：plus.barcode 扫码（filters / order 生效） */
function scanByApp(options: HmScanOptions): void {
  // #ifdef APP-PLUS
  const settler = createSettler(options)
  const filters = options.filters || []
  const order = options.order || []

  const qs = [
    filters.length ? `filters=${encodeURIComponent(filters.join(','))}` : '',
    order.length ? `order=${encodeURIComponent(order.join(','))}` : '',
  ].filter(Boolean).join('&')

  uni.navigateTo({
    url: `/uni_modules/hm-scan/pages/hm-scan${qs ? `?${qs}` : ''}`,
    events: {
      // 扫码页回传：识别成功
      hmScanMarked: (payload: { type: number, result: string, charSet?: string }) => {
        settler.success({
          result: payload?.result ?? '',
          scanType: mapAppType(payload?.type),
          charSet: payload?.charSet ?? '',
        })
      },
      // 扫码页回传：失败 / 用户取消
      hmScanFail: (payload: { errMsg?: string }) => {
        settler.fail({ errMsg: payload?.errMsg || 'scanCode:fail' })
      },
    },
    fail: (err: any) => {
      settler.fail({ errMsg: err?.errMsg || 'scanCode:fail navigateTo' })
    },
  })
  // #endif
}

/** 小程序 / 其他端：降级为 uni.scanCode（filters 只能收窄 scanType 大类） */
function scanByUni(options: HmScanOptions): void {
  uni.scanCode({
    onlyFromCamera: options.onlyFromCamera ?? false,
    scanType: toUniScanType(options.filters) as any,
    success: (res: any) => {
      options.success?.({
        result: res?.result ?? '',
        scanType: res?.scanType ?? '',
        charSet: res?.charSet ?? '',
        ...(res?.code !== undefined ? { code: res.code } : {}),
      })
    },
    fail: (err: any) => {
      options.fail?.({ errMsg: err?.errMsg || 'scanCode:fail' })
    },
    complete: () => options.complete?.(),
  })
}

/**
 * 扫码入口
 * @see HmScanOptions
 */
export function hmScan(options: HmScanOptions = {}): void {
  // #ifdef APP-PLUS
  scanByApp(options)
  // #endif

  // #ifdef MP
  scanByUni(options)
  // #endif

  // #ifdef H5
  // H5 端请使用 H5 扫码引擎（html5-qrcode）；此处直接失败，避免静默无响应
  options.fail?.({ errMsg: 'hmScan: H5 端请使用 H5 扫码引擎' })
  options.complete?.()
  // #endif
}

/** 便捷方法：只认 CODE128 的扫码（本次业务问题的直接解法） */
export function hmScanCode128(
  options: Omit<HmScanOptions, 'filters'> = {},
): void {
  hmScan({ ...options, filters: ['code128'] })
}

export type { HmScanError, HmScanFormat, HmScanOptions, HmScanResult }
export { APP_FILTER_VALUE, APP_TYPE_MAP, H5_FORMAT_VALUE, toUniScanType } from './type'
