package com.e7orbit.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.e7orbit.R

@DrawableRes
internal fun heroElementIconRes(attribute: String): Int? = when (attribute.lowercase()) {
    "dark" -> R.drawable.e7_element_dark
    "wind", "earth" -> R.drawable.e7_element_earth
    "fire" -> R.drawable.e7_element_fire
    "ice" -> R.drawable.e7_element_ice
    "light" -> R.drawable.e7_element_light
    else -> null
}

@DrawableRes
internal fun heroClassIconRes(role: String): Int? = when (role.lowercase()) {
    "assassin" -> R.drawable.e7_class_assassin
    "knight" -> R.drawable.e7_class_knight
    "mage" -> R.drawable.e7_class_mage
    "manauser", "soulweaver" -> R.drawable.e7_class_manauser
    "ranger" -> R.drawable.e7_class_ranger
    "warrior" -> R.drawable.e7_class_warrior
    else -> null
}

@Composable
internal fun HeroIdentityIcons(
    attribute: String?,
    role: String?,
    rarity: Int?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attribute?.let(::heroElementIconRes)?.let { resId ->
            HeroAssetIcon(resId, null, iconSize)
        }
        if (attribute?.let(::heroElementIconRes) != null && role?.let(::heroClassIconRes) != null) {
            Spacer(Modifier.width(4.dp))
        }
        role?.let(::heroClassIconRes)?.let { resId ->
            HeroAssetIcon(resId, null, iconSize)
        }
        if ((attribute?.let(::heroElementIconRes) != null || role?.let(::heroClassIconRes) != null) && rarity != null) {
            Spacer(Modifier.width(6.dp))
        }
        rarity?.let { HeroStars(stars = it, iconSize = iconSize) }
    }
}

@Composable
internal fun HeroStars(
    stars: Int,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
) {
    val count = stars.coerceIn(1, 6)
    val step = iconSize.value * 0.8f
    val width = (iconSize.value + step * (count - 1)).dp
    Box(
        modifier = modifier
            .width(width)
            .height(iconSize),
    ) {
        repeat(count) { index ->
            Image(
                painter = painterResource(R.drawable.e7_hero_star),
                contentDescription = if (index == 0) "$count 星" else null,
                modifier = Modifier
                    .offset(x = (step * index).dp)
                    .size(iconSize),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun HeroAssetIcon(
    @DrawableRes resId: Int,
    contentDescription: String?,
    size: Dp,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
