# Pixel Measure (Google Pixel 專用高效能 AR 空間測量與螢幕尺規)

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack-Compose%20M3%20Expressive-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Vulkan](https://img.shields.io/badge/Graphics-Vulkan%201.4-red.svg)](https://developer.android.com/games/vulkan)
[![ARCore](https://img.shields.io/badge/AR-Google%20ARCore%20%2B%20Camera2-orange.svg)](https://developers.google.com/ar)

專為 **Google Pixel** 與 **Vulkan 1.4** 高效能硬體加速打造的頂級空間測量與日常尺規 Android 應用程式。結合 **Material 3 Expressive** 設計系統、智慧空間平面偵測、多軸感測器融合與原生系統轉場動畫，提供毫秒級零延遲、極致穩定流暢的專業量測體驗。

---

## 🌟 核心特色與亮點

### 1. 🚀 Vulkan 1.4 與 Google Pixel 高效能 AR 渲染
- **Vulkan 1.4 繪圖管線**：透過 `VulkanArGraphicsPipeline` 實現硬體加速 3D 空間網格投影與點雲渲染，解鎖 60Hz/120Hz LTPO 螢幕自适应流暢更新。
- **Camera2 共享相機 (SharedCamera)**：ARCore 與高速 Camera2 零拷貝管線無縫交接，確保取景畫面清晰低延遲。
- **Pixel 續航與散熱調優**：整合 `PixelPerformanceMonitor` 動態熱平衡與效能模式。

### 2. ✨ Material 3 Expressive 頂級互動介面
- **動態色彩 (Dynamic Color)**：全面支援 Android 12+ Material You 動態色彩，涵蓋引導膠囊、警示橫幅、對話框與導覽按鈕。
- **Expressive 複合元件**：
  - **分割按鈕 (ExpressiveSplitButton)** 與 **連結式動作群組**（手電筒亮度調節、歷史紀錄、設定）。
  - **48dp+ 觸控目標標準**：確保所有互動元件符合 Google 無障礙存取規範。
  - **懸浮藥丸導覽與折疊屏支援**：完美適應手機、摺疊機（Tabletop / Book 姿態）與平板（List-Detail 雙欄工作區）。

### 3. 📐 專業級多模態空間與日常測量
- **智慧自動偵測 (Auto-Detect)**：自動識別點對點距離、折線、多邊形面積、3D 圓形直徑與垂直高度。
- **3D 立體體積與角度量測**：支援長寬高三維立方體體積運算與三點夾角測量。
- **雙線卡尺與實體螢幕直尺**：高精確度螢幕刻度尺，支援字體縮放、實機尺寸校正、公制 ($mm/cm$) 與英制 ($inch$) 切換。

### 4. 🛡️ 極致穩定性架構與系統原生轉場
- **Activity 架構解耦**：將設定頁面升格為獨立原生 `SettingsActivity`，由 Android 系統原生接管頁面打開與返回動畫（支援 Predictive Back 預測性返回）。
- **純粹淡入淡出轉場**：AR 模式與螢幕尺規切換採用乾淨利落的 `fadeIn` / `fadeOut` 動畫。
- **執行緒安全生命週期管理**：完美消除 ARCore 與 Camera2 銷毀競態條件，從設定頁面返回時相機永不凍結。

### 5. 💾 本地持久化與完整歷史管理
- 支援測量紀錄分類篩選、影片快照預覽、備註編輯、CSV 報表匯出與分享。

---

## 🛠️ 專案架構與模組

```
app/src/main/java/com/example/
├── logic/
│   ├── ar/                # ARCore 引擎、空間追蹤與幾何投影數學 (ModernArEngine.kt)
│   ├── camera/            # 高速 Camera2 管理器與錄影編碼 (HighSpeedCamera2Manager.kt)
│   ├── vulkan/            # Vulkan 1.4 繪圖管線與效能監控 (VulkanArGraphicsPipeline.kt)
│   └── sensor/            # 空間感測器融合與抖動過濾 (SensorCorrectionEngine.kt)
├── ui/
│   ├── adaptive/          # 摺疊屏、平板 Window Size Class 與機軸追蹤
│   ├── components/        # M3 Expressive 複合按鈕、頂欄、導覽列、尺規組件
│   ├── screens/           # 主畫面、設定頁、歷史紀錄與歡迎導覽
│   └── theme/             # Material 3 Expressive 色彩、字型 (GoogleSansRounded)
├── data/
│   └── db/                # Room 本地持久化資料庫與資料實體
└── MainActivity.kt        # 應用程式入口與重新整理頻率校準
```

---

## 📄 授權條款

本專案採用 [MIT License](LICENSE) 開源授權。
