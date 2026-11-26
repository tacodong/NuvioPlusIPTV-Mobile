# Nuvio + IPTV for Mac

An unofficial macOS build of [Nuvio + IPTV](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile): homelessbrian's Live TV, guide, Sports, catch-up, recordings and On Demand, running on the [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop) app, with layouts made for a Mac window.

This branch follows both projects. homelessbrian's `dev` and Nuvio Desktop's `Dev` are merged in as they change, so new features from either side arrive here without being redone.

## What's different on Mac

- **Live TV guide:** the Android TV app's layout. A two-hour window that fills the width, channel numbers and logos, an info panel with the show's poster, and Recordings and Guide display buttons.
- **Live preview (off by default):** click a channel to preview it, muted, in the info panel; click it again for full screen. Turn it on under ⚙ Guide display. It uses noticeably more CPU, memory and battery while a preview plays.
- **Sports:** league cards in a grid, and a Pick your teams picker (sport, league, teams, search).
- **Mouse and trackpad:** right-click for channel options, scroll the guide sideways, and swipe or use the arrows on the Home hero.
- **Lighter on resources:** the guide is kept on disk rather than in memory, and normal (SDR) video is drawn without HDR compositing.

Phones and tablets keep their own layouts: the wide ones only switch on in the desktop app (one setting, `LiveTvWideLayoutsOnTablets`, turns them on for tablets). The one change they share is the Pick your teams picker, which replaces a dead end on mobile.

## Install

1. Download `Nuvio+IPTV-macOS-arm64.zip` from the latest release (Apple silicon).
2. Unzip it and move **Nuvio+IPTV** to Applications.
3. The app isn't signed by Apple yet, so macOS blocks it the first time. In Terminal, run:

   ```bash
   xattr -dr com.apple.quarantine "/Applications/Nuvio+IPTV.app"
   ```

4. Open it and sign in to your Nuvio account. Your Live TV setup follows your account, like on TV and phone.

Nuvio+IPTV keeps its own data separate from the regular Nuvio app, so you can have both installed.

## Not there yet

- Multiview
- Intel Macs (Apple silicon only for now)
- Signed, notarised builds
- Portal (Stalker) channels that need the provider's own authenticated player

## Building it yourself

The same as Nuvio Desktop: JDK 17, then `./gradlew :composeApp:createDistributable`. The app is in `composeApp/build/compose/binaries/main/app/`. Sign-in needs `NUVIO_SUPABASE_URL` and `NUVIO_SUPABASE_ANON_KEY` in `local.properties`.
