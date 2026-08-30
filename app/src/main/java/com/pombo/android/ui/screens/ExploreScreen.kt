package com.pombo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pombo.android.AppViewModel
import com.pombo.android.data.Channel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.theme.PomboColors


@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun ExploreTab(vm: AppViewModel, onCreate: () -> Unit, onConnect: () -> Unit) {
    val items by vm.explore.collectAsState()
    val loading by vm.exploreLoading.collectAsState()
    val status by vm.status.collectAsState()
    // Preview sender labels resolve ENS at render (the name lands after the fetch).
    val ensNames by vm.ensNames.collectAsState()
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("All") }
    /** Web #explore-language-filter: "" is All Languages. */
    var language by remember { mutableStateOf("") }
    var privateOnly by remember { mutableStateOf(false) }
    // Access-type marker (N-D): "open" | "gated" | "paid" ("" = all).
    // Explore OPENS on Open — gate-backed storefronts are a tap away.
    var accessFilter by remember { mutableStateOf("open") }
    var categoriesExpanded by remember { mutableStateOf(false) }
    var passwordFor by remember { mutableStateOf<com.pombo.android.ExploreChannel?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadExplore() }

    val isGuest by vm.isGuest.collectAsState()
    val exploreImages by vm.channelImages.collectAsState()
    val exploreImagesPending by vm.channelImagesPending.collectAsState()
    val nsfw by vm.nsfwEnabled.collectAsState()
    // Web rebuilds the template without the NSFW/Adult chips when the setting
    // turns off — an active selection of one of them resets with it.
    androidx.compose.runtime.LaunchedEffect(nsfw) {
        if (!nsfw && (category == "NSFW" || category == "Adult")) category = "All"
    }

    passwordFor?.let { target ->
        ChannelPasswordDialog(
            channelName = target.name,
            onDismiss = { passwordFor = null },
            onSubmit = { pwd -> passwordFor = null; vm.joinChannel(target.messageStreamId, pwd) }
        )
    }
    Column(Modifier.fillMaxSize()) {
        // Guests get the same orange "Create Account" call to action as in
        // Chats, in the same header slot; accounts get "create channel".
        PomboHeader(status) {
            if (isGuest) {
                Row(
                    Modifier.background(PomboColors.Accent, RoundedCornerShape(12.dp))
                        .clickableNoRipple(onConnect)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Create Account", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                // Web #header-create-channel-btn: `px-4 py-1.5 rounded-xl
                // bg-[#F6851B]/15 border-[#F6851B]/30` with a WHITE plus — a
                // rounded rectangle, not the circle we had, and the glyph is
                // white rather than orange.
                Box(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .clickableNoRipple(onCreate)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, contentDescription = "Create channel", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
        // Only PomboHeader stays fixed — the section label, search, filters
        // and chips now scroll away with the list as one continuous region
        // (2026-08-20 user call: was a separate static block above the
        // LazyColumn, unlike every other row here which already scrolled).
        val shownExplore = items.filter { c ->
            val matchesQuery = query.isBlank() ||
                c.name.contains(query, true) || c.description.contains(query, true)
            val matchesCategory = category == "All" || c.category.equals(categoryValue(category), true)
            // Web `filterChannels` (N-D semantics): password channels live
            // behind the Private chip — their access is a secret shared
            // out-of-band; everything else, public AND gate-backed
            // (gated/paid), is a storefront and lists in the main view.
            val matchesType = if (privateOnly) c.type == "password" else c.type != "password"
            // Access markers: Open = public; Gated vs Paid split by the gate
            // MODE (unresolved counts as Gated until the cached read lands).
            val matchesAccess = when (accessFilter) {
                "open" -> c.type == "public"
                "gated" -> c.type == "gated" && c.gateMode != 3
                "paid" -> c.type == "gated" && c.gateMode == 3
                else -> true
            }
            // Web filterChannels: NSFW/Adult channels stay hidden unless that
            // very category is selected or "Show Sensitive Content" is on.
            val sensitive = c.category.equals("nsfw", true) || c.category.equals("adult", true)
            val nsfwOk = nsfw || category == "NSFW" || category == "Adult" || !sensitive
            // Web: the language filter only applies once a specific one is
            // picked; "All Languages" is the empty value.
            val matchesLanguage = language.isEmpty() || c.language.equals(language, true)
            matchesQuery && (privateOnly || matchesCategory) && matchesType && matchesAccess && nsfwOk && matchesLanguage
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp, end = 14.dp, bottom = PILL_NAV_BOTTOM_OFFSET + PILL_NAV_HEIGHT + 12.dp
            )
        ) {
        item {
        Spacer(Modifier.height(8.dp))
        // Access filters left, search + language icons right (2026-08-21
        // user call, was bookended). Search collapses to an icon and expands
        // into a field only when tapped; language drops the "All Languages"
        // text label for an icon-only trigger. Expanded search takes the row
        // on its own — filters + language move to a second row, same as the
        // web.
        val accessFilterPills: @Composable () -> Unit = {
            // Open / Gated / Paid — exclusive, selecting only. Gated vs Paid is the on-chain gate MODE.
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("open" to "Open", "gated" to "Gated", "paid" to "Paid").forEachIndexed { i, (value, label) ->
                    if (i > 0) Text(
                        "|", color = Color.White.copy(alpha = 0.15f), fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    val active = accessFilter == value
                    Text(
                        label,
                        color = if (active) Color.Black else Color.White.copy(alpha = 0.50f),
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier
                            .background(if (active) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickableNoRipple {
                                if (!active) {
                                    accessFilter = value
                                    // Password view and the markers are disjoint universes
                                    privateOnly = false
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        val searchFocusRequester = remember { FocusRequester() }
        LaunchedEffect(searchExpanded) { if (searchExpanded) searchFocusRequester.requestFocus() }
        // Search expands in place, between the pills and the language icon —
        // it never pushes the pills to a second row (2026-08-21 user call).
        Row(verticalAlignment = Alignment.CenterVertically) {
            accessFilterPills()
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (searchExpanded) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                            .border(1.dp, PomboColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Search, contentDescription = null,
                            tint = PomboColors.Accent, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color.White),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                            modifier = Modifier.weight(1f).height(38.dp).focusRequester(searchFocusRequester)
                                .wrapContentHeight(Alignment.CenterVertically),
                            decorationBox = { inner ->
                                if (query.isEmpty()) Text(
                                    "Search",
                                    color = Color.White.copy(alpha = 0.30f), fontSize = 14.sp
                                )
                                inner()
                            }
                        )
                        Icon(
                            Icons.Filled.Close, contentDescription = "Close search",
                            tint = Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(16.dp).clickableNoRipple { searchExpanded = false; query = "" }
                        )
                    }
                } else {
                    Box(
                        Modifier.size(38.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(11.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(11.dp))
                            .clickableNoRipple { searchExpanded = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Search, contentDescription = "Search channels",
                            tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            ExploreLanguageFilter(language) { language = it }
        }
        Spacer(Modifier.height(10.dp))

        // Category chips. Collapsed they scroll sideways in one rail; the
        // chevron expands them into rows so every filter is visible at once
        // (web: .explore-category-rail + #explore-toggle-categories-btn).
        val selectPrivate = { if (!privateOnly) { privateOnly = true; accessFilter = "" } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (categoriesExpanded) {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) { ExploreChips(category, privateOnly, nsfw, { category = it; privateOnly = false }, selectPrivate) }
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) { ExploreChips(category, privateOnly, nsfw, { category = it; privateOnly = false }, selectPrivate) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (categoriesExpanded) "Collapse filters" else "Show all filters",
                tint = Color.White.copy(alpha = 0.40f),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (categoriesExpanded) 180f else 0f)
                    .clickableNoRipple { categoriesExpanded = !categoriesExpanded }
            )
        }
        Spacer(Modifier.height(12.dp))
        } // end header item (label, search, access filters, category chips)

        when {
            // Web: spinner + "Loading channels..." while the list is empty.
            loading && items.isEmpty() -> item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.40f), strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Loading channels...", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                }
            }
            shownExplore.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text("No channels found", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                }
            }
            else -> items(shownExplore, key = { it.messageStreamId }) { ch ->
                // Web parity: an Explore tap previews the channel; joining
                // only happens through the Join button in the chat header.
                val adminStreamId = com.pombo.android.core.StreamConstants.deriveAdminId(ch.messageStreamId)
                ExploreCard(
                    ch,
                    image = exploreImages[adminStreamId],
                    imagePending = adminStreamId in exploreImagesPending,
                    ensNames = ensNames
                ) {
                    // Protected channels need the password before anything
                    // can be decrypted, so ask up front (web JoinChannelUI).
                    // Gated routes by mode: TOKEN/NFT holders get a real
                    // preview (browse before committing); PAID and
                    // non-holders go through the join flow (entry screen).
                    when {
                        ch.type == "password" -> passwordFor = ch
                        ch.type == "gated" -> vm.openGatedExplore(ch)
                        else -> vm.previewChannel(ch)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        }
    }
}


/**
 * Full category set from the web ExploreUI (orderedCategoryChips). NSFW
 * categories stay behind the same opt-in the web uses.
 */
private val EXPLORE_CATEGORIES = listOf(
    "All", "General", "News", "Crypto", "Finance", "Politics", "Science",
    "Gaming", "Sports", "Health", "Tech & AI", "Entertainment", "Education", "Comedy"
)

/** Maps a chip label to the metadata value stored in the channel. */
private fun categoryValue(label: String): String = when (label) {
    "Tech & AI" -> "tech"
    else -> label.lowercase()
}

/**
 * Web #explore-language-filter — icon-only trigger (2026-08-20/21 redesign,
 * was a full "All Languages" text label + chevron). Same option list, same
 * empty value for "All Languages", and a highlighted ring when a specific
 * language is picked so the collapsed icon still signals an active filter.
 */
@Composable
private fun ExploreLanguageFilter(selected: String, onPick: (String) -> Unit) {
    val options = remember {
        listOf(
            "" to "All Languages", "en" to "English", "pt" to "Português",
            "es" to "Español", "fr" to "Français", "de" to "Deutsch",
            "it" to "Italiano", "zh" to "中文", "ja" to "日本語",
            "ko" to "한국어", "ru" to "Русский", "ar" to "العربية",
            "other" to "Other"
        )
    }
    var open by remember { mutableStateOf(false) }
    val active = selected.isNotEmpty()
    Box {
        Box(
            Modifier.size(38.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(11.dp))
                .border(1.dp, if (active) PomboColors.Accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(11.dp))
                .clickableNoRipple { open = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Public, contentDescription = "Language filter",
                tint = if (active) PomboColors.Accent else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Color(0xFF1E1E1E))
        ) {
            options.forEach { (value, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (value == selected) Color.White else Color.White.copy(alpha = 0.70f),
                            fontSize = 14.sp
                        )
                    },
                    onClick = { onPick(value); open = false }
                )
            }
        }
    }
}

/**
 * Explore cards run slightly tighter than the web's type scale — the sizes are
 * a straight port of the Tailwind classes, which were set for a desktop-first
 * card. One factor for every text in the card (and its line heights), so the
 * internal proportions stay exactly as designed and a future nudge is one line.
 */
private const val EXPLORE_TEXT_SCALE = 0.92f
private val Number.exp get() = (toFloat() * EXPLORE_TEXT_SCALE).sp

@Composable
private fun ExploreCard(
    ch: com.pombo.android.ExploreChannel,
    image: ByteArray?,
    /** True while this channel's image fetch is still in flight (web: _exploreResolvedImages). */
    imagePending: Boolean = false,
    /** Resolved ENS names (address -> name) for the preview's sender label. */
    ensNames: Map<String, String> = emptyMap(),
    onOpen: () -> Unit
) {
    // Web card is bg-white/[0.03]; on the phone's OLED black that read too
    // dark, so the card sits a touch lighter for contrast against the
    // background (user call, 2026-08-18).
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .clickableNoRipple(onOpen)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // Channel thumb: `rounded-full`, 56×56, avatar fallback at 0.5.
            // Web parity: a spinner while the fetch is still in flight, the
            // generated avatar only once it has genuinely come back empty —
            // without the distinction this flashed straight to the fallback
            // avatar and then swapped to the real image a moment later.
            when {
                image != null -> androidx.compose.foundation.Image(
                    bitmap = remember(image) {
                        android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size).asImageBitmap()
                    },
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(CircleShape)
                )
                imagePending -> Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.04f)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.40f), strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                }
                else -> Avatar(ch.messageStreamId, size = 56.dp, cornerRadiusFraction = 0.5)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Web readOnlyBadge: a megaphone BEFORE the name, white/60.
                    // The CSS is `w-3.5 md:w-5` (14px mobile, 20px desktop);
                    // 14 read too faint on the card, so this sits between them.
                    if (ch.readOnly) {
                        Icon(
                            Icons.Outlined.Campaign, contentDescription = "Read-only",
                            tint = Color.White.copy(alpha = 0.60f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        ch.name, color = Color.White.copy(alpha = 0.90f), fontSize = 16.exp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
                if (ch.description.isNotEmpty()) {
                    // `text-base text-white/40 mt-1 line-clamp-2`
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ch.description, color = Color.White.copy(alpha = 0.40f),
                        fontSize = 16.exp, lineHeight = 21.exp, maxLines = 2
                    )
                }
                if (ch.lastText.isNotEmpty()) {
                    // `.explore-preview-line mt-3 ml-3`: 0.875rem, white/50,
                    // 3 lines on mobile, and indented 12px past the description.
                    Spacer(Modifier.height(12.dp))
                    val ensLabel = ch.lastSenderAddress.takeIf { it.isNotEmpty() }
                        ?.let { ensNames[it.lowercase()] }
                    Text(
                        "${ensLabel ?: ch.lastSender}: ${ch.lastText}",
                        color = Color.White.copy(alpha = 0.50f), fontSize = 14.exp,
                        lineHeight = 18.exp, maxLines = 3,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
            // Web: a chevron on the right of every card, `w-4 h-4 text-white/15`.
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.size(16.dp)
            )
        }
        // Tags (category / language) — `px-1.5 py-1 bg-white/5 text-white/50
        // text-[11px] rounded`, category hidden when it is the default.
        val tags = listOfNotNull(
            ch.category.takeIf { it.isNotEmpty() && !it.equals("general", true) }?.replaceFirstChar { it.uppercase() },
            ch.language.takeIf { it.isNotEmpty() }?.uppercase()
        )
        // Access stack (N-D) + tags share ONE zone: the stack (VERB / VALUE /
        // QUALIFIER) anchors the center, the badges sit on the bottom-right
        // corner at the same height — one row of card height instead of two,
        // and no orphaned lone badge. Subscribe = recurring (accent-tinted
        // verb); Hold = mere possession, "in your wallet" = "you pay nothing".
        if (ch.gateVerb != null || tags.isNotEmpty() || ch.authorMode != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()) {
                ch.gateVerb?.let { verb ->
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            verb.uppercase(),
                            color = if (ch.gateMode == 3) PomboColors.Accent.copy(alpha = 0.70f)
                                else Color.White.copy(alpha = 0.40f),
                            fontSize = 10.exp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            ch.gateValue ?: "",
                            color = Color.White.copy(alpha = 0.90f),
                            fontSize = 15.exp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            ch.gateQualifier ?: "",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 11.exp
                        )
                    }
                }
                if (tags.isNotEmpty() || ch.authorMode != null) {
                    // Audience icon (never identity — both modes guarantee
                    // authorship to participants) rides centered above the
                    // LAST tag, in the card-metadata corner.
                    Row(
                        Modifier.align(Alignment.BottomEnd),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        val tagText: @Composable (String) -> Unit = { t ->
                            Text(
                                t,
                                color = Color.White.copy(alpha = 0.50f), fontSize = 11.exp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                        tags.dropLast(1).forEach { tagText(it) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ch.authorMode?.let { mode ->
                                Icon(
                                    if (mode == "members") Icons.Outlined.People else Icons.Outlined.Public,
                                    contentDescription = if (mode == "members")
                                        "Authors visible to members only" else "Author on the wire",
                                    tint = Color.White.copy(alpha = 0.50f),
                                    modifier = Modifier.size(16.dp)
                                )
                                if (tags.isNotEmpty()) Spacer(Modifier.height(4.dp))
                            }
                            tags.lastOrNull()?.let { tagText(it) }
                        }
                    }
                }
            }
        }
    }
}


/** Chips shared by the collapsed rail and the expanded grid. */
@Composable
private fun ExploreChips(
    category: String,
    privateOnly: Boolean,
    nsfwEnabled: Boolean,
    onCategory: (String) -> Unit,
    onTogglePrivate: () -> Unit
) {
    // Web getTemplate: the NSFW and Adult chips exist only while "Show
    // Sensitive Content" is on, appended after the regular categories.
    val cats = if (nsfwEnabled) EXPLORE_CATEGORIES + listOf("NSFW", "Adult") else EXPLORE_CATEGORIES
    cats.forEach { cat ->
        val active = cat == category && !privateOnly
        Text(
            cat,
            color = if (active) PomboColors.Background else Color.White.copy(alpha = 0.60f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(if (active) Color.White else Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                .clickableNoRipple { onCategory(cat) }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
    // Private is a separate axis in the web too (#explore-private-chip):
    // it filters by access type, not by topic, so it toggles on its own.
    Row(
        Modifier
            .background(
                if (privateOnly) Color.White else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(6.dp)
            )
            .clickableNoRipple(onTogglePrivate)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Lock, contentDescription = null,
            tint = if (privateOnly) PomboColors.Background else Color.White.copy(alpha = 0.60f),
            modifier = Modifier.size(11.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "Private",
            color = if (privateOnly) PomboColors.Background else Color.White.copy(alpha = 0.60f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium
        )
    }
}
