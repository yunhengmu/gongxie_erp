<template>
  <view class="hm-scan">
    <!--
      相机预览由 plus.barcode 原生控件提供（创建后通过 append 挂到 webview），
      它自带扫码框与扫描线，层级在 webview 之上，因此这里不自绘遮罩，
      只保留浮在原生层之上的返回按钮与闪光灯开关。
    -->

    <!-- 顶部返回 -->
    <view class="hm-scan__navbar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="hm-scan__back" @click="handleCancel">
        <text class="hm-scan__back-icon">‹</text>
      </view>
      <text class="hm-scan__title">扫一扫</text>
      <view class="hm-scan__placeholder" />
    </view>

    <!-- 底部操作区 -->
    <view class="hm-scan__actions">
      <view v-if="flashSupported" class="hm-scan__action" @click="toggleFlash">
        <text class="hm-scan__action-icon">{{ flashOn ? '☀' : '☼' }}</text>
        <text class="hm-scan__action-text">{{ flashOn ? '关闭闪光灯' : '闪光灯' }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onBackPress, onLoad, onReady, onUnload } from '@dcloudio/uni-app'
import { ref } from 'vue'
import type { HmScanFormat } from '../js_sdk/type'
// #ifdef APP-PLUS
import { toAppFilters, toAppOrder } from '../js_sdk/index'
// #endif

/**
 * hm-scan 扫码页（仅 App 端使用）
 *
 * 由 js_sdk/index.ts 通过 uni.navigateTo + events 唤起：
 *   - 识别成功 → emit 'hmScanMarked' { type, result, charSet } 并返回
 *   - 失败/取消 → emit 'hmScanFail'  { errMsg } 并返回
 *
 * 参数通过 query 传入：
 *   filters  逗号分隔的码制白名单，如 "code128"
 *   order    逗号分隔的码制顺序
 *
 * 两个必须遵守的约束：
 *  1. <script setup> 顶层严禁访问 uni.* / plus.* / getCurrentPages()。
 *     顶层代码在模块初始化阶段执行，此时 App 端运行时尚未注入这些全局对象，
 *     会抛 "uni is not defined" 并导致整包白屏。
 *  2. onBackPress 里严禁调用 uni.navigateBack —— 它会再次触发 onBackPress，
 *     形成无限递归（RangeError: Maximum call stack size exceeded）。
 */

const statusBarHeight = ref(0)
const flashOn = ref(false)
const flashSupported = ref(false)

// #ifdef APP-PLUS
let barcode: any = null
let appFilters: string[] = []
let appOrder: string[] = []
/** 已识别到结果，用于阻止重复上报 */
let marked = false
/** 正在关闭页面，用于阻止 navigateBack ↔ onBackPress 递归 */
let closing = false
/** 重试 append 的定时器句柄 */
let appendRetryTimer: any = null
// #endif

/** 解析码制查询串 */
function parseFormats(v?: string): HmScanFormat[] {
  if (!v)
    return []
  try {
    return decodeURIComponent(v).split(',').filter(Boolean) as HmScanFormat[]
  } catch {
    return []
  }
}

/** 只做回传，不负责关闭页面 */
function emitToOpener(event: string, payload: any) {
  try {
    const pages = getCurrentPages()
    const current: any = pages[pages.length - 1]
    const channel = current?.getOpenerEventChannel?.()
    channel?.emit?.(event, payload)
  } catch (e) {
    console.error('[hm-scan] emit 失败', e)
  }
}

/**
 * 回传并关闭页面。
 * 本函数调用的 uni.navigateBack 会再次触发 onBackPress，
 * 因此 onBackPress 里绝对不能调用本函数，这里用 closing 哨兵保证只执行一次。
 */
function emitAndBack(event: string, payload: any) {
  // #ifdef APP-PLUS
  if (closing)
    return
  closing = true
  marked = true
  // #endif

  emitToOpener(event, payload)

  uni.navigateBack({
    delta: 1,
    fail: () => {
      // 极端情况下（页面栈异常）退回首页，避免卡在扫码页
      uni.reLaunch({ url: '/pages/index/index' })
    },
  })
}

/** 点击返回按钮：回传失败态并关闭页面 */
function handleCancel() {
  emitAndBack('hmScanFail', { errMsg: 'scanCode:cancel' })
}

// #ifdef APP-PLUS
function toggleFlash() {
  if (!barcode) {
    uni.showToast({ title: '相机尚未就绪', icon: 'none' })
    return
  }
  const next = !flashOn.value
  try {
    barcode.setFlash(next)
    flashOn.value = next
  } catch (e) {
    console.error('[hm-scan] setFlash 失败', e)
    uni.showToast({ title: '当前设备不支持闪光灯', icon: 'none' })
  }
}

/** 识别成功 */
function handleMarked(type: number, result: string) {
  if (marked || !result)
    return
  marked = true
  try {
    barcode?.close?.()
  } catch {
    // ignore
  }
  barcode = null
  emitAndBack('hmScanMarked', { type, result, charSet: '' })
}

/** 把原生控件挂到当前 webview，成功返回 true */
function appendBarcode(): boolean {
  try {
    // uni-app 中 plus.webview.currentWebview() 拿到的是逻辑层 webview
    // （id 为 __uniapp__service），原生控件挂上去不显示。
    // 必须用页面实例的 $getAppWebview() 取到渲染层 webview。
    const pages = getCurrentPages()
    const current: any = pages[pages.length - 1]
    const currentWebview = current?.$getAppWebview?.()
      || (plus as any).webview.currentWebview()
    if (!currentWebview || !barcode)
      return false
    currentWebview.append(barcode)
    console.log('[hm-scan] append barcode 到 webview', currentWebview.id)
    return true
  } catch (e) {
    console.error('[hm-scan] append barcode 失败', e)
    return false
  }
}

/** 初始化 plus.barcode：先确认相机权限，再创建并启动控件 */
function initBarcode() {
  const nav = (plus as any).navigator
  let status = 'unknown'
  try {
    status = nav?.checkPermission?.('camera') || 'unknown'
  } catch (e) {
    console.log('[hm-scan] checkPermission 异常', e)
  }
  console.log('[hm-scan] camera permission =', status)

  if (status === 'authorized' || status === 'granted') {
    createAndStart()
    return
  }

  try {
    nav?.requestPermission?.('camera', (res: string) => {
      console.log('[hm-scan] requestPermission camera =', res)
      if (res === 'authorized' || res === 'granted')
        createAndStart()
      else
        uni.showToast({ title: '未获得相机权限，请在系统设置中开启', icon: 'none' })
    }, (err: any) => {
      console.log('[hm-scan] requestPermission 失败', err)
      uni.showToast({ title: '未获得相机权限，请在系统设置中开启', icon: 'none' })
    })
  } catch (e) {
    console.log('[hm-scan] requestPermission 异常', e)
    createAndStart()
  }
}

/** 创建原生扫码控件并启动相机 */
function createAndStart() {
  // plus.barcode.create 的第二个参数即「码制尝试列表」，
  // 不传 = 全部码制；传入 [CODE128] 后内核只按 CODE128 解析，
  // 从根本上杜绝被误判成 CODE_25 / UPC_E 的可能。
  // filters 必须是「数字常量数组」（如 plus.barcode.CODE128），
  // 传字符串（如 'code128'）原生层不认，会表现为相机不识别任何码制、扫不出内容。
  const dict = (plus as any).barcode
  const candidateNames = appOrder.length ? appOrder : appFilters
  const candidates = candidateNames
    .map(name => dict?.[name])
    .filter((v: any) => typeof v === 'number')

  // 原生控件位于 webview 上层，按「顶部导航 + 底部操作区」预留高度，避免遮住按钮。
  const systemInfo = uni.getSystemInfoSync()
  const topInset = statusBarHeight.value + 44
  const bottomInset = 150
  const previewHeight = Math.max(240, (systemInfo.windowHeight || 640) - topInset - bottomInset)

  try {
    // 注意 1：第一个参数是「扫码控件自身的标识」，不是要挂载的 DOM 节点 id。
    // 注意 2：top/left/width/height 必须显式给出，否则控件可能不显示。
    // 注意 3：position 用 'absolute'，让原生预览层浮在 webview 之上；
    //         用 'static' 时会被页面里绝对定位的元素盖住，表现为「相机没起来」。
    barcode = (plus as any).barcode.create('hmScanBarcode', candidates.length ? candidates : undefined, {
      left: '0px',
      width: '100%',
      // 原生控件位于 WebView 上层，避开页面按钮区域，确保返回和闪光灯可点击。
      top: `${topInset}px`,
      height: `${previewHeight}px`,
      position: 'absolute',
      // 原生控件自带扫码框与扫描线
      frameColor: '#0957DE',
      scanbarColor: '#0957DE',
    })
  } catch (e) {
    console.error('[hm-scan] barcode.create 失败', e)
    uni.showToast({ title: '相机启动失败，请检查相机权限', icon: 'none' })
    return
  }

  barcode.onmarked = (type: number, result: string) => handleMarked(type, result)
  barcode.onerror = (err: any) => {
    console.error('[hm-scan] barcode error', err)
  }

  // 关键：create 只是创建对象，并不会把它显示到页面上，
  // 必须 append 到当前 webview 窗口，否则永远是黑屏。
  // onReady 时 webview 可能尚未完全就绪，因此失败后按间隔重试若干次。
  if (appendBarcode())
    startBarcode()
  else
    retryAppend(0)
}

/** append 失败后的重试（最多 10 次，间隔 200ms） */
function retryAppend(times: number) {
  if (times >= 10) {
    console.error('[hm-scan] append barcode 重试耗尽')
    uni.showToast({ title: '相机启动失败，请重新进入', icon: 'none' })
    return
  }
  appendRetryTimer = setTimeout(() => {
    if (appendBarcode())
      startBarcode()
    else
      retryAppend(times + 1)
  }, 200)
}

/** 开始扫码并探测闪光灯 */
function startBarcode() {
  try {
    barcode.start()
    console.log('[hm-scan] barcode.start 已调用')
  } catch (e) {
    console.error('[hm-scan] barcode.start 失败', e)
    uni.showToast({ title: '相机启动失败，请检查相机权限', icon: 'none' })
    return
  }

  // setFlash 只能在 start() 之后、预览就绪时调用；立即调用在部分机型会抛错，
  // 因此延后一拍。探测失败也保留按钮，点击时再给出提示。
  flashSupported.value = true
  setTimeout(() => {
    try {
      barcode?.setFlash(false)
    } catch {
      // 部分机型确实不支持闪光灯，保持按钮可见，点击时再提示
    }
  }, 500)
}
// #endif

// #ifndef APP-PLUS
function toggleFlash() {}
// #endif

onLoad((options: any) => {
  // 状态栏高度等系统信息统一在生命周期内取，避免顶层访问 uni 导致白屏
  try {
    statusBarHeight.value = uni.getSystemInfoSync().statusBarHeight || 0
  } catch {
    statusBarHeight.value = 0
  }

  // #ifdef APP-PLUS
  appFilters = toAppFilters(parseFormats(options?.filters))
  appOrder = toAppOrder(parseFormats(options?.order), parseFormats(options?.filters))
  // #endif
})

onReady(() => {
  // #ifdef APP-PLUS
  initBarcode()
  // #endif
  // #ifndef APP-PLUS
  emitAndBack('hmScanFail', { errMsg: 'hm-scan: 该平台请使用 uni.scanCode' })
  // #endif
})

onUnload(() => {
  // #ifdef APP-PLUS
  closing = true
  marked = true
  if (appendRetryTimer) {
    clearTimeout(appendRetryTimer)
    appendRetryTimer = null
  }
  try {
    barcode?.close?.()
  } catch {
    // ignore
  }
  barcode = null
  // #endif
})

/**
 * Android 物理返回键。
 *
 * 这里只做「回传 + 让系统自己关页面」，绝对不能调用 uni.navigateBack ——
 * navigateBack 会再次触发 onBackPress，形成无限递归。
 * 返回 false 表示不拦截，由运行时按默认行为返回上一页。
 */
onBackPress(() => {
  // #ifdef APP-PLUS
  if (closing)
    return false
  closing = true
  marked = true
  // #endif
  emitToOpener('hmScanFail', { errMsg: 'scanCode:cancel' })
  return false
})
</script>

<style lang="scss" scoped>
.hm-scan {
  position: relative;
  width: 100%;
  height: 100vh;
  background-color: #000;
  overflow: hidden;
}

/*
 * 顶部返回与底部闪光灯开关。
 * plus.barcode 原生预览层挂在 webview 之上，用 z-index 未必能压过它，
 * 因此这里给按钮加了半透明底色，保证在任何预览画面上都能看清。
 */
.hm-scan__navbar {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 999;
  width: 100%;
  display: flex;
  flex-direction: row;
  align-items: center;
  height: 88rpx;
  box-sizing: content-box;
  background-color: rgba(0, 0, 0, 0.35);
}

.hm-scan__back {
  width: 88rpx;
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.hm-scan__back-icon {
  font-size: 64rpx;
  color: #fff;
  line-height: 1;
}

.hm-scan__title {
  flex: 1;
  text-align: center;
  font-size: 34rpx;
  color: #fff;
}

.hm-scan__placeholder {
  width: 88rpx;
  height: 88rpx;
}

.hm-scan__actions {
  position: absolute;
  bottom: 80rpx;
  left: 0;
  z-index: 999;
  width: 100%;
  display: flex;
  flex-direction: row;
  justify-content: center;
  gap: 120rpx;
}

.hm-scan__action {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  padding: 16rpx 24rpx;
  border-radius: 16rpx;
  background-color: rgba(0, 0, 0, 0.35);
}

.hm-scan__action-icon {
  font-size: 48rpx;
  color: #fff;
  line-height: 1;
}

.hm-scan__action-text {
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.9);
}
</style>
