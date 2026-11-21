# PlushPal accessories

Drop files in these folders (also works from a resource pack: `assets/<your namespace>/accessories/...`).
Press F3+T in game to reload. Turn accessories on in the F12 screen > Accessories (they start off).

    accessories/
      models/       Bedrock .geo.json files (same coordinate space as the plush)
      animations/   Bedrock .animation.json files
      texture/      .png files
      binding/      one .json per accessory; any file name; this is what gets imported

## binding/<anything>.json

    {
      "name": "Cape",                      // shown in the settings screen
      "description": "Your cape ...",      // tooltip in the settings screen
      "model": "cape.geo.json",            // file in models/        (required)
      "anim": "cape.animation.json",       // file in animations/    (optional)
      "texture": "cape_template.png",      // file in texture/       (optional)
      "set_skin_texture": true,            // use the player's own texture instead (optional, default false)
      "skin_texture": "cape",              // "cape" = the player's cape, "skin" = the player's skin (default "skin")
      "attach_to": "plush",                // bone it hangs from: plush, torso, head, leftarm, ... (default "plush")
      "animations": { "idle": "swaycape" },  // see "Animations" below
      "default_enabled": false             // (optional, default false)
    }

With set_skin_texture, if the player has no cape (or the skin isn't being used in that view),
the `texture` file is used instead. `setskintexture` is accepted as an alias of `set_skin_texture`.
Comments are not allowed in real JSON; remove the // notes above in your own file.

## Animations

`animations` maps the plush's states to animation names from the `anim` file. State names:
`idle`, `swim_idle`, `fall`, `equip`, `hi`, `yay`, `clap`, `attack`, `mine`, `on_fire`, `freeze`
(friendly aliases such as `falling`, `freezing`, `swimming`, `burning` also work), plus `always`.

    "animations": {
      "idle": "swaycape",
      "fall": "capefall",
      "freezing": { "animation": "capefreeze", "speed": 1.0, "rotation_intensity": 1.0,
                    "position_intensity": 1.0, "enabled": true },
      "always": "extra_overlay"
    }

* A value is an animation name, or an object with the same attributes the plush's own animations have
  (`animation`, `speed`, `rotation_intensity`, `position_intensity`, `enabled`).
* **`idle` plays no matter what.** It is also the fallback: whenever the plush is in a state the accessory has no
  animation for (or that state's animation already finished), `idle` keeps playing, continuously. So a cape with
  only `"idle": "swaycape"` sways during equip, falling, freezing and everything else.
* A state's own animation replaces `idle` while the plush is in that state and starts when the state starts.
* `always` plays on top of everything, even when a state animation is playing. It may be a list.
* The old form `"animations": ["name"]` still works and means `always`. If `animations` is missing, every
  looping animation in the file plays as `always`.
* Switching between an accessory's animations fades over the same "Transition time" as the plush.
