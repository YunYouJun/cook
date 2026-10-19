# 0006 iOS App 的数据统计与反馈

状态：已采纳（2026-10-19）

## 背景

云食册（原生 iOS App）准备上 TestFlight，需要知道用户怎么用、哪里出问题，同时要尽量少运维，不想处理 ATT 弹窗、复杂的隐私标签和数据出境。Web 端已有两套统计：Microsoft Clarity（访问、热力图、回放，见 `docs/dev/analytics.md`）和 CloudBase `cook-feedback` 云函数（菜谱反馈、匿名筛选信号，见 `docs/dev/data.md`）。

## 方案对比

| 方案                                                            | 优点                                                                        | 问题                                                   |
| --------------------------------------------------------------- | --------------------------------------------------------------------------- | ------------------------------------------------------ |
| Apple 自带：App Store Connect 分析、TestFlight、Xcode Organizer | 零代码；下载、留存、来源、崩溃、卡顿都有；Apple 自己收集                    | 只有同意共享的用户；没有自定义事件                     |
| 复用 CloudBase `cook-feedback`                                  | 和 Web 同一口径，直接进 `pnpm eval:search` 和数据审查；数据在国内；不加 SDK | 只覆盖筛选和反馈，不是通用埋点                         |
| Firebase Analytics                                              | 功能全                                                                      | 国内基本不可达，数据出境                               |
| 友盟+                                                           | 国内可达                                                                    | 要同意弹窗、SDK 合规清单和延迟初始化，维护成本高       |
| Clarity iOS SDK                                                 | 和 Web 同一项目，有回放                                                     | 回放采集界面、输入框要逐个遮罩，数据出境，需要同意弹窗 |

## 决定

- iOS 不接第三方统计 SDK。下载、留存、崩溃看 App Store Connect、TestFlight 和 Xcode Organizer。
- iOS 上报和 Web 相同的筛选信号（没有结果、点开第几个结果）和菜谱反馈，并新增不针对菜谱的「意见反馈」（Web 和 iOS 共用）。iOS 通过云函数的 HTTP 访问地址 `POST` 提交。
- iOS 的设备 ID 每次启动随机生成、只放内存，不写盘、不读 IDFV；设置里可以关闭筛选信号。
- 不读取 IDFA、不做跨 App 追踪，因此不需要 ATT。

## 影响

- App Store 隐私标签申报：搜索历史、产品交互（筛选信号），其他用户内容和联系信息（意见反馈），都不关联身份、不用于追踪。`PrivacyInfo.xcprivacy` 与之一致。
- 云函数的 `POST` 入口不需要口令，滥用风险和 Web 匿名调用相同，靠每设备每天 200 次限额兜底。
- 以后确实需要 iOS 回放或通用事件时，优先评估 Clarity iOS SDK，并补上同意流程。
