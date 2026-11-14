// 模組地圖（草圖）。依賴方向只有一條：app 往下指，core 什麼都不依賴。
//
//   :phone ──► :quotes ──► :core
//      └──────────────────► :core
//   :wear  ──────────────► :core
//
// :core    純 Kotlin/JVM。領域型別、估值、提醒狀態機、抓取排程政策、存檔／同步編碼。JVM 單元測試全在這裡。
// :quotes  純 Kotlin/JVM。對外只有 QuoteFetcher 一個介面與一個工廠；Yahoo／期交所的網址、JSON DTO、代號對照全是 internal。
// :phone   Android app。Compose UI、DataStore、WorkManager、通知、Data Layer 發布。薄殼。
// :wear    Android app（Wear OS 7）。Tile、錶面小格、Compose App、Data Layer 接收。不連網、不寫任何狀態。
//
// 版本以 grounding.md 為準：AGP 9.5.1、Kotlin 2.4.21、compileSdk/targetSdk 37、Compose BOM 2026.09.00、
// Wear Compose Material3 1.7.1、Tiles 1.6.2、ProtoLayout Material3 1.4.2、
// watchface-complications-data-source-ktx 1.3.0、play-services-wearable 20.0.1、WorkManager 2.12.0、
// DataStore 1.3.0、kotlinx-serialization-json 1.13.0。Room 刻意不用（理由見 design.md〈接受的取捨〉）。
// 另外需要但 grounding 沒列版本、實作前要查的：kotlinx-coroutines-play-services（Task.await）、
// androidx.concurrent:concurrent-futures-ktx（Tile 的 ListenableFuture 橋接）。

rootProject.name = "holdings"
include(":core", ":quotes", ":phone", ":wear")
