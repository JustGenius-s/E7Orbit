# 国服 1920×1080 识图素材

此目录对应 MuMu 12 的 1920×1080 横屏帧缓冲。识图前会统一缩放到 1024×576，因此 `regions.json` 和模板均使用 1024×576 坐标，匹配出的点击位置会映射回 1920×1080。

2026-07-14 已从国服截图裁剪 10 项必需模板：

- `shop_anchor.png`
- `covenant_item.png`
- `mystic_item.png`
- `purchase_button.png`
- `covenant_confirm.png`
- `mystic_confirm.png`
- `confirm_purchase.png`
- `refresh_button.png`
- `refresh_dialog.png`
- `confirm_refresh.png`
- `resource_insufficient.png`（首版暂不处理，配置为可选）

首版暂不识别资源不足提示；其余必需模板已补齐。

## 待补充的讨伐点击模板

讨伐流程已经改为只点击 OpenCV 定位结果，不再使用固定屏幕坐标。以下模板定义已加入
`regions.json`，补充对应 PNG 后讨伐模板健康检查才会通过：

- `hunt_action_open_battle.png`
- `hunt_action_open_selection.png`
- `hunt_action_select_hell.png`
- `hunt_action_disable_quick_battle.png`
- `hunt_action_enable_managed_battle.png`
- `hunt_action_start_battle.png`
- `hunt_action_open_delegation.png`
- `hunt_action_confirm_delegation.png`
- `hunt_action_open_managed_status.png`
- `hunt_action_stop_managed.png`
