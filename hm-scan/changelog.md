## 1.0.0（2026-09-11）

- 首次发布。
- 对标 `uni.scanCode` 的扫码 API，新增 `filters`（码制白名单）与 `order`（码制尝试顺序）。
- App 端基于 `plus.barcode` 实现，解决内核把 CODE128 误判成 CODE_25 / UPC_E 导致结果错误的问题。
- 提供 App 端扫码页 `pages/hm-scan.vue`（原生预览层 + 自绘取景框 / 返回 / 闪光灯 / 相册）。
- H5 端生效 `filters`（走 html5-qrcode）；小程序端降级为 `uni.scanCode`，仅能收窄 scanType 大类。
