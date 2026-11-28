package com.ngstudio.spindledrop.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.core.data.Economy
import com.ngstudio.spindledrop.core.data.PlayerProfile
import com.ngstudio.spindledrop.game.Sfx
import com.ngstudio.spindledrop.game.level.Difficulty
import com.ngstudio.spindledrop.game.theme.Catalog
import com.ngstudio.spindledrop.game.theme.Price
import com.ngstudio.spindledrop.game.theme.Rarity
import com.ngstudio.spindledrop.game.theme.Worlds
import com.ngstudio.spindledrop.ui.Toasts
import com.ngstudio.spindledrop.ui.components.BallImage
import com.ngstudio.spindledrop.ui.components.ChestIcon
import com.ngstudio.spindledrop.ui.components.CoinIcon
import com.ngstudio.spindledrop.ui.components.GameButton
import com.ngstudio.spindledrop.ui.components.GameDialog
import com.ngstudio.spindledrop.ui.components.GameToggle
import com.ngstudio.spindledrop.ui.components.GemIcon
import com.ngstudio.spindledrop.ui.components.GlowPulse
import com.ngstudio.spindledrop.ui.components.LockIcon
import com.ngstudio.spindledrop.ui.components.SplatImage
import com.ngstudio.spindledrop.ui.components.StrokeText
import com.ngstudio.spindledrop.ui.components.TrailPreview
import com.ngstudio.spindledrop.ui.components.WorldPreview
import com.ngstudio.spindledrop.ui.components.noRippleClick
import com.ngstudio.spindledrop.ui.components.rememberClick
import com.ngstudio.spindledrop.ui.theme.GC
import com.ngstudio.spindledrop.ui.theme.GT
import com.ngstudio.spindledrop.ui.theme.LocalServices
import com.ngstudio.spindledrop.ui.theme.darken
import com.ngstudio.spindledrop.ui.theme.lighten
import com.ngstudio.spindledrop.ui.theme.toColor
import com.ngstudio.spindledrop.ui.theme.toComposeColor

private enum class Kind { BALL, TRAIL, SPLAT }

private class ShopItem(val kind: Kind, val id: String, val name: String, val rarity: Rarity, val price: Price)

private fun items(kind: Kind): List<ShopItem> = when (kind) {
    Kind.BALL -> Catalog.balls.map { ShopItem(kind, it.id, it.name, it.rarity, it.price) }
    Kind.TRAIL -> Catalog.trails.map { ShopItem(kind, it.id, it.name, it.rarity, it.price) }
    Kind.SPLAT -> Catalog.splats.map { ShopItem(kind, it.id, it.name, it.rarity, it.price) }
}

private fun owned(p: PlayerProfile, it: ShopItem) = when (it.kind) {
    Kind.BALL -> it.id in p.ownedBalls
    Kind.TRAIL -> it.id in p.ownedTrails
    Kind.SPLAT -> it.id in p.ownedSplats
}

private fun equipped(p: PlayerProfile, it: ShopItem) = when (it.kind) {
    Kind.BALL -> p.ball == it.id
    Kind.TRAIL -> p.trail == it.id
    Kind.SPLAT -> p.splat == it.id
}

@Composable
private fun ItemPreview(it: ShopItem, size: androidx.compose.ui.unit.Dp, p: PlayerProfile) {
    when (it.kind) {
        Kind.BALL -> BallImage(Catalog.ball(it.id), size)
        Kind.TRAIL -> TrailPreview(Catalog.trail(it.id), Modifier.size(size))
        Kind.SPLAT -> SplatImage(Catalog.splat(it.id).tile, Catalog.ball(p.ball).splat.toComposeColor(), size)
    }
}

@Composable
fun ShopTab(p: PlayerProfile) {
    val s = LocalServices.current
    var cat by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<ShopItem?>(null) }
    var chestOpen by remember { mutableStateOf(false) }
    val tabs = listOf("Balls", "Trails", "Splats", "Chests")
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(top = topBarSpace()),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = DockSpace + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(3) }) { ShopHero(p) }
            item(span = { GridItemSpan(3) }) {
                Row(Modifier.fillMaxWidth().background(Color(0x55000000), RoundedCornerShape(20.dp)).padding(4.dp)) {
                    tabs.forEachIndexed { i, t ->
                        val sel = cat == i
                        val click = rememberClick { cat = i }
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (sel) Brush.verticalGradient(listOf(GC.Yellow.lighten(0.2f), GC.Yellow)) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                                .noRippleClick(click),
                            contentAlignment = Alignment.Center,
                        ) { StrokeText(t, size = 16.sp, strokeWidth = 2.dp, stroke = if (sel) GC.Yellow.darken(0.6f) else GC.Ink, maxLines = 1) }
                    }
                }
            }
            if (cat < 3) {
                val kind = Kind.entries[cat]
                items(items(kind), key = { it.kind.name + it.id }) { item ->
                    ItemCard(item, p) {
                        if (owned(p, item)) {
                            s.audio.sfx(Sfx.UI_TOGGLE)
                            s.profile.update { pr ->
                                when (item.kind) {
                                    Kind.BALL -> pr.copy(ball = item.id)
                                    Kind.TRAIL -> pr.copy(trail = item.id)
                                    Kind.SPLAT -> pr.copy(splat = item.id)
                                }
                            }
                        } else confirm = item
                    }
                }
            } else {
                item(span = { GridItemSpan(3) }) { ChestsPanel(p, onOpen = { chestOpen = true }) }
            }
        }
        PurchaseDialog(confirm, p, onClose = { confirm = null })
        ChestDialog(chestOpen, p) { chestOpen = false }
    }
}

@Composable
private fun ShopHero(p: PlayerProfile) {
    val ball = Catalog.ball(p.ball)
    val inf = rememberInfiniteTransition(label = "hero")
    val bob by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "bob")
    Row(
        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF5A2FD0), Color(0xFF2A1170))), RoundedCornerShape(26.dp))
            .border(2.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(26.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
            GlowPulse(ball.rarity.color.toColor(), Modifier.size(120.dp))
            BallImage(ball, 96.dp, Modifier.offset(y = (kotlin.math.sin(bob) * 5).dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("EQUIPPED", style = GT.body(12.sp, GC.Muted, androidx.compose.ui.text.font.FontWeight.Bold))
            StrokeText(ball.name, size = 26.sp, align = TextAlign.Start, maxLines = 1)
            Text("Trail: ${Catalog.trail(p.trail).name}", style = GT.body(14.sp))
            Text("Splat: ${Catalog.splat(p.splat).name}", style = GT.body(14.sp))
            Spacer(Modifier.height(4.dp))
            Text("${p.ownedBalls.size}/${Catalog.balls.size} balls collected", style = GT.body(12.sp, GC.Yellow))
        }
    }
}

@Composable
private fun ItemCard(item: ShopItem, p: PlayerProfile, onClick: () -> Unit) {
    val own = owned(p, item)
    val eq = equipped(p, item)
    val rc = item.rarity.color.toColor()
    val sc by animateFloatAsState(if (eq) 1.04f else 1f, spring(dampingRatio = 0.5f), label = "card")
    val click = rememberClick(Sfx.UI_TAP, onClick)
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.scale(sc).clip(shape)
            .background(Brush.verticalGradient(listOf(rc.darken(0.35f), Color(0xFF1E0B4C))))
            .border(if (eq) 3.dp else 1.5.dp, if (eq) GC.Yellow else rc.copy(alpha = 0.7f), shape)
            .noRippleClick(click).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            ItemPreview(item, 72.dp, p)
            if (!own) Box(Modifier.align(Alignment.TopEnd)) { LockIcon(18.dp) }
        }
        Text(item.name, style = GT.body(13.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, textAlign = TextAlign.Center)
        Text(item.rarity.label, style = GT.body(11.sp, rc.lighten(0.3f)))
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(30.dp)
                .background(
                    when { eq -> GC.Mint; own -> GC.Sky; else -> Color(0x66000000) },
                    RoundedCornerShape(15.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                eq -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(3.dp))
                    Text("Equipped", style = GT.display(13.sp))
                }
                own -> Text("Equip", style = GT.display(14.sp))
                else -> PriceTag(item.price, 14)
            }
        }
    }
}

@Composable
fun PriceTag(price: Price, size: Int = 16) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (price.gems > 0) GemIcon((size + 4).dp) else CoinIcon((size + 4).dp)
        Spacer(Modifier.width(4.dp))
        Text("%,d".format(if (price.gems > 0) price.gems else price.coins), style = GT.display(size.sp))
    }
}

@Composable
private fun PurchaseDialog(item: ShopItem?, p: PlayerProfile, onClose: () -> Unit) {
    val s = LocalServices.current
    var last by remember { mutableStateOf<ShopItem?>(null) }
    if (item != null) last = item
    val it = last
    GameDialog(item != null, onDismiss = onClose, title = "Unlock", titleColor = GC.Yellow) {
        if (it == null) return@GameDialog
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                GlowPulse(it.rarity.color.toColor(), Modifier.size(150.dp))
                ItemPreview(it, 120.dp, p)
            }
            StrokeText(it.name, size = 28.sp)
            Text(it.rarity.label, style = GT.body(15.sp, it.rarity.color.toColor().lighten(0.3f)))
            Spacer(Modifier.height(16.dp))
            val afford = Economy.canAfford(p, it.price)
            GameButton(
                text = "%,d".format(if (it.price.gems > 0) it.price.gems else it.price.coins),
                onClick = {
                    if (!afford) {
                        s.audio.sfx(Sfx.ERROR)
                        Toasts.show(if (it.price.gems > 0) "Not enough gems yet" else "Not enough coins yet - play more levels!")
                        return@GameButton
                    }
                    s.audio.sfx(Sfx.PURCHASE)
                    s.profile.update { pr ->
                        val paid = Economy.pay(pr, it.price)
                        when (it.kind) {
                            Kind.BALL -> paid.copy(ownedBalls = paid.ownedBalls + it.id, ball = it.id)
                            Kind.TRAIL -> paid.copy(ownedTrails = paid.ownedTrails + it.id, trail = it.id)
                            Kind.SPLAT -> paid.copy(ownedSplats = paid.ownedSplats + it.id, splat = it.id)
                        }
                    }
                    Toasts.show("${it.name} unlocked!")
                    onClose()
                },
                color = if (afford) GC.Mint else Color(0xFF7D7895),
                modifier = Modifier.fillMaxWidth(),
                leading = { if (it.price.gems > 0) GemIcon(30.dp) else CoinIcon(32.dp) },
                sfx = Sfx.UI_TAP,
                sub = if (afford) "Buy & equip" else if (it.price.gems > 0) "Need ${it.price.gems - p.gems} more gems" else "Need ${"%,d".format(it.price.coins - p.coins)} more coins",
            )
            if (!afford) {
                Spacer(Modifier.height(8.dp))
                Text("Clear levels, finish missions and spin the wheel to earn more.", style = GT.body(13.sp, GC.Muted), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ChestsPanel(p: PlayerProfile, onOpen: () -> Unit) {
    val s = LocalServices.current
    val inf = rememberInfiniteTransition(label = "cp")
    val r by inf.animateFloat(-4f, 4f, infiniteRepeatable(tween(260), androidx.compose.animation.core.RepeatMode.Reverse), label = "cpr")
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF6A3A1A), Color(0xFF2A1170))), RoundedCornerShape(26.dp))
            .border(2.dp, GC.Yellow.copy(alpha = 0.6f), RoundedCornerShape(26.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            GlowPulse(GC.Yellow, Modifier.size(170.dp))
            ChestIcon(130.dp, Modifier.graphicsLayer { rotationZ = if (p.chests > 0) r else 0f })
        }
        StrokeText("You have ${p.chests} chest${if (p.chests == 1) "" else "s"}", size = 24.sp)
        Text("Chests hold coins, gems, balls and trails. Earn them from boss levels, the Trophy Road, the wheel and day 7 rewards.",
            style = GT.body(14.sp, GC.Muted), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        GameButton(if (p.chests > 0) "OPEN CHEST" else "BUY CHEST", onClick = {
            if (p.chests > 0) onOpen() else {
                if (p.coins >= 1000) { s.audio.sfx(Sfx.PURCHASE); s.profile.update { it.copy(coins = it.coins - 1000, chests = it.chests + 1) } }
                else { s.audio.sfx(Sfx.ERROR); Toasts.show("A chest costs 1,000 coins") }
            }
        }, color = GC.Orange, modifier = Modifier.fillMaxWidth(), shine = p.chests > 0, sub = if (p.chests > 0) null else "1,000 coins")
        if (com.ngstudio.spindledrop.BuildConfig.DEBUG && s.ads.isAvailable) {
            Spacer(Modifier.height(10.dp))
            GameButton("FREE 100 COINS", onClick = {
                s.ads.showRewarded("free_coins") { ok -> if (ok) { s.profile.update { it.copy(coins = it.coins + 100) }; s.audio.sfx(Sfx.REWARD); Toasts.show("+100 coins!") } }
            }, color = GC.Sky, modifier = Modifier.fillMaxWidth(), height = 50.dp, fontSize = 19.sp, sub = "Watch ad")
        }
    }
}

// ============================================================================ themes

@Composable
fun ThemesTab(p: PlayerProfile) {
    val s = LocalServices.current
    var buy by remember { mutableIntStateOf(-1) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = DockSpace + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(top = topBarSpace()),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(
                Modifier.fillMaxWidth().background(Color(0x66000000), RoundedCornerShape(22.dp)).padding(16.dp),
            ) {
                StrokeText("World Themes", size = 28.sp, align = TextAlign.Start)
                Text("Each chapter takes you to a new world. Lock a favourite world to play every level in it.", style = GT.body(14.sp, GC.Muted))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto: follow the level worlds", style = GT.body(16.sp), modifier = Modifier.weight(1f))
                    GameToggle(p.lockedTheme == null, onChange = { on -> if (on) s.profile.update { it.copy(lockedTheme = null) } })
                }
            }
        }
        items(Worlds.all.size) { i ->
            val w = Worlds.all[i]
            val own = Economy.themeOwned(p, i, w.id)
            val sel = p.lockedTheme == w.id
            val click = rememberClick {
                if (own) s.profile.update { it.copy(lockedTheme = if (sel) null else w.id) } else buy = i
            }
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier.clip(shape).background(Color(0xFF1E0B4C)).border(if (sel) 3.dp else 1.5.dp, if (sel) GC.Yellow else Color.White.copy(alpha = 0.2f), shape).noRippleClick(click),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.9f)) {
                    WorldPreview(w, Modifier.fillMaxSize())
                    if (!own) Box(Modifier.fillMaxSize().background(Color(0x77000000)), contentAlignment = Alignment.Center) { LockIcon(40.dp) }
                    if (sel) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).background(GC.Mint, CircleShape).padding(4.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    StrokeText(w.name, size = 17.sp, strokeWidth = 2.dp, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    when {
                        sel -> Text("Locked in", style = GT.body(13.sp, GC.Yellow))
                        own -> Text("Tap to lock", style = GT.body(13.sp, GC.Muted))
                        else -> PriceTag(Catalog.themePrice(i), 14)
                    }
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Text("Worlds you reach in the level map unlock for free. New palettes (Dusk, Night, Neon…) appear every ${Difficulty.WORLD_COUNT * 10} levels.",
                style = GT.body(13.sp, GC.Muted), textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
    }
    val idx = buy
    GameDialog(idx >= 0, onDismiss = { buy = -1 }, title = "Unlock World", titleColor = GC.Mint) {
        if (idx < 0) return@GameDialog
        val w = Worlds.all[idx]
        val price = Catalog.themePrice(idx)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            WorldPreview(w, Modifier.size(160.dp, 180.dp).clip(RoundedCornerShape(20.dp)))
            Spacer(Modifier.height(10.dp))
            StrokeText(w.name, size = 26.sp)
            Text("Or reach level ${idx * 10 + 1} to unlock it for free.", style = GT.body(14.sp, GC.Muted), textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            GameButton("%,d".format(price.coins), onClick = {
                if (Economy.canAfford(p, price)) {
                    s.audio.sfx(Sfx.PURCHASE)
                    s.profile.update { Economy.pay(it, price).copy(ownedThemes = it.ownedThemes + w.id, lockedTheme = w.id) }
                    buy = -1
                } else { s.audio.sfx(Sfx.ERROR); Toasts.show("Not enough coins yet") }
            }, color = GC.Mint, modifier = Modifier.fillMaxWidth(), leading = { CoinIcon(30.dp) })
        }
    }
}
