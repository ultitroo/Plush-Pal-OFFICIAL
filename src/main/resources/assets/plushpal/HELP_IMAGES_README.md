# Help pictures for the settings screen

Hover a setting's **name** in the F12 screen and its description appears in a tooltip. If a picture exists for that
setting, it is shown under the description (it fades in after the text). Drop PNGs in
`assets/plushpal/textures/gui/help/` (or a resource pack: `assets/plushpal/textures/gui/help/`). F3+T reloads them.

* Any size works. It is scaled to about 170 px wide (max 110 tall) and keeps its proportions, so use the same
  proportions (for example 16:9) for all of them.
* **File name** = the setting's name in lower case with underscores. For example *Global speed* ->
  `global_speed.png`.
* Two places are checked, in this order: `help/<tab>/<name>.png` (only that tab), then `help/<name>.png`
  (any tab that has a setting with that name, e.g. *Rotation amount* exists in General and Animations).
* No picture = no picture shown. Nothing breaks.

## Names by tab

### General  (`help/general/...`)

* Animations -> `animations.png`
* UI animations -> `ui_animations.png`
* UI animation speed -> `ui_animation_speed.png`
* Transition time -> `transition_time.png`
* Global speed -> `global_speed.png`
* Rotation amount -> `rotation_amount.png`
* Movement amount -> `movement_amount.png`
* Low health (hearts) -> `low_health_hearts.png`
* Fall trigger speed -> `fall_trigger_speed.png`

### Scale  (`help/scale/...`)

* Global scale -> `global_scale.png`
* First person -> `first_person.png`
* Third person -> `third_person.png`
* Inventory -> `inventory.png`
* On the ground -> `on_the_ground.png`
* Item frame / pop -> `item_frame_pop.png`
* On head -> `on_head.png`

### Layout  (`help/layout/...`)

* Animate in inventory -> `animate_in_inventory.png`
* Animate on ground -> `animate_on_ground.png`
* Animate in frames / pop -> `animate_in_frames_pop.png`
* Animate on head -> `animate_on_head.png`
* Offset X -> `offset_x.png`
* Offset Y -> `offset_y.png`
* Offset Z -> `offset_z.png`

### Animations  (`help/animations/...`)

* Enabled -> `enabled.png`
* Speed -> `speed.png`
* Rotation amount -> `rotation_amount.png`
* Movement amount -> `movement_amount.png`
* Animation -> `animation.png`

### Emotes  (`help/emotes/...`)

* Quick emote (G) -> `quick_emote_g.png`
* Emote wheel -> `emote_wheel.png`
* Hi -> `hi.png`
* Yay -> `yay.png`
* Clap -> `clap.png`
* Slot 1 -> `slot_1.png`
* Slot 2 -> `slot_2.png`
* Slot 3 -> `slot_3.png`
* Slot 4 -> `slot_4.png`
* Slot 5 -> `slot_5.png`
* Slot 6 -> `slot_6.png`

### Particles  (`help/particles/...`)

* Particles (beta) -> `particles_beta.png`
* Flames -> `flames.png`
* Bubbles -> `bubbles.png`
* Snowflakes -> `snowflakes.png`
* Amount -> `amount.png`
* Size -> `size.png`

### Sound  (`help/sound/...`)

* Plush sound -> `plush_sound.png`
* Plush volume -> `plush_volume.png`
* Plush pitch -> `plush_pitch.png`
* Menu sounds -> `menu_sounds.png`
* Menu volume -> `menu_volume.png`

### Skin  (`help/skin/...`)

* Use my skin -> `use_my_skin.png`
* Slim skin support -> `slim_skin_support.png`

### Accessories (cosmetics)  (`help/cosmetics/...`)

* Animate accessories -> `animate_accessories.png`
* (one per accessory: its name, e.g. Cape) -> `<accessory name>.png`
