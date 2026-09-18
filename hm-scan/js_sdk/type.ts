/**
 * hm-scan 类型定义与码制映射
 *
 * 码制 key 统一使用下划线小写形式（如 code128 / code39 / ean13），
 * 对内对外都用这一套 key，各平台再各自翻译成自己认识的值。
 */

/** 本模块支持的码制（key 为统一写法） */
export type HmScanFormat
  = | 'code128'
    | 'code39'
    | 'code93'
    | 'codabar'
    | 'ean13'
    | 'ean8'
    | 'upca'
    | 'upce'
    | 'itf'
    | 'datamatrix'
    | 'pdf417'
    | 'qr'

/** 扫码结果，字段与 uni.scanCode 的 success 回调一致 */
export interface HmScanResult {
  /** 条码内容 */
  result: string
  /** 内核判定的码制（统一写法，如 'code128'） */
  scanType: string
  /** 字符集，H5/App 均原样透传，取不到时为空串 */
  charSet: string
  /** 原始扫码数据（uni.scanCode 在部分端会返回，取不到时不下发该字段） */
  code?: string
}

/** 扫码入参：前 5 项与 uni.scanCode 对齐，后 2 项为本模块新增 */
export interface HmScanOptions {
  /** 是否只能从相机扫码（false 时可从相册选图），默认 false —— 与 uni.scanCode 一致 */
  onlyFromCamera?: boolean
  /** 是否保留扫码界面（仅小程序端有效），默认 false */
  keep?: boolean
  /** 扫码成功回调 */
  success?: (res: HmScanResult) => void
  /** 扫码失败/取消回调 */
  fail?: (err: HmScanError) => void
  /** 无论成功失败都会执行 */
  complete?: () => void

  /** 【新增】码制白名单：只识别列出的码制；不传 = 全开（保持 uni.scanCode 行为） */
  filters?: HmScanFormat[]
  /** 【新增】码制尝试顺序：越靠前越优先；不传 = 内核默认顺序 */
  order?: HmScanFormat[]
}

export interface HmScanError {
  errMsg: string
  [k: string]: any
}

/** App 端提示文案：该方法在 H5 端不适用 */
export const ERR_H5_NOT_SUPPORTED = 'hm-scan: H5 端请使用 hmScan 的 H5 分支（html5-qrcode）'

/**
 * plus.barcode 的数字枚举 → 统一写法映射。
 *
 * plus.barcode 的 onmarked 回调第一个参数是数字 type，
 * 需要在运行时用 plus.barcode.CODE_128 等常量对照，故此处是「常量名 → 统一 key」。
 */
export const APP_TYPE_MAP: Record<string, HmScanFormat> = {
  CODE128: 'code128',
  CODE39: 'code39',
  CODE93: 'code93',
  CODABAR: 'codabar',
  EAN13: 'ean13',
  EAN8: 'ean8',
  UPCA: 'upca',
  UPCE: 'upce',
  ITF: 'itf',
  DATAMATRIX: 'datamatrix',
  PDF417: 'pdf417',
  QR: 'qr',
}

/**
 * plus.barcode.create 的 filters 需要「数字常量数组」，此处存放的是
 * plus.barcode 上的常量名，运行时再通过 plus.barcode[常量名] 取到数字值。
 */
export const APP_FILTER_VALUE: Record<HmScanFormat, string> = {
  code128: 'CODE128',
  code39: 'CODE39',
  code93: 'CODE93',
  codabar: 'CODABAR',
  ean13: 'EAN13',
  ean8: 'EAN8',
  upca: 'UPCA',
  upce: 'UPCE',
  itf: 'ITF',
  datamatrix: 'DATAMATRIX',
  pdf417: 'PDF417',
  qr: 'QR',
}

/** html5-qrcode 的 formatsToSupport 使用它自己的枚举名 */
export const H5_FORMAT_VALUE: Record<HmScanFormat, string> = {
  code128: 'CODE_128',
  code39: 'CODE_39',
  code93: 'CODE_93',
  codabar: 'CODABAR',
  ean13: 'EAN_13',
  ean8: 'EAN_8',
  upca: 'UPC_A',
  upce: 'UPC_E',
  itf: 'ITF',
  datamatrix: 'DATA_MATRIX',
  pdf417: 'PDF_417',
  qr: 'QR_CODE',
}

/** uni.scanCode 的 scanType 只有三大类，用于小程序端降级 */
export function toUniScanType(filters?: HmScanFormat[]): string[] {
  if (!filters?.length)
    return ['barCode', 'qrCode']

  const types = new Set<string>()
  for (const f of filters) {
    if (f === 'qr')
      types.add('qrCode')
    else if (f === 'datamatrix' || f === 'pdf417')
      types.add('datamatrix')
    else
      types.add('barCode')
  }
  return [...types]
}
