package com.e7orbit.ui

import androidx.annotation.DrawableRes
import com.e7orbit.R

@DrawableRes
internal fun imprintPositionIconRes(position: String): Int? = when (position.lowercase()) {
    "all" -> R.drawable.e7_imprint_position_all
    "br" -> R.drawable.e7_imprint_position_br
    "lb" -> R.drawable.e7_imprint_position_lb
    "lr" -> R.drawable.e7_imprint_position_lr
    "tb" -> R.drawable.e7_imprint_position_tb
    "tl" -> R.drawable.e7_imprint_position_tl
    "tr" -> R.drawable.e7_imprint_position_tr
    "concentration" -> R.drawable.e7_imprint_position_concentration
    else -> null
}

@DrawableRes
internal fun imprintRankIconRes(rank: String): Int? = when (rank.uppercase()) {
    "B" -> R.drawable.e7_imprint_rank_b
    "A" -> R.drawable.e7_imprint_rank_a
    "S" -> R.drawable.e7_imprint_rank_s
    "SS" -> R.drawable.e7_imprint_rank_ss
    "SSS" -> R.drawable.e7_imprint_rank_sss
    else -> null
}

internal fun imprintPositionDescription(position: String): String = when (position.lowercase()) {
    "all" -> "全队位置"
    "br" -> "下方和右侧位置"
    "lb" -> "左侧和下方位置"
    "lr" -> "左侧和右侧位置"
    "tb" -> "上方和下方位置"
    "tl" -> "上方和左侧位置"
    "tr" -> "上方和右侧位置"
    "concentration" -> "自身位置"
    else -> position
}
