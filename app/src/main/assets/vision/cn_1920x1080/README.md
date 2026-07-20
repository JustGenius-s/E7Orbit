# 国服 1920×1080 识图素材

此目录的模板由 MuMu 12 的 1920×1080 横屏截图制作，`regions.json` 和模板均使用
1024×576 参考坐标。运行时保持原始截图不变，只按实际横屏内容比例等比缩放模板；ROI
会自动贴左、居中或贴右，匹配结果直接使用设备坐标点击。

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
