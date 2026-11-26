# Nuvio + IPTV Mobile 1.1.0-beta.3-iptv.2

## What's new

### Recordings
- **Extra time per series:** long-press a series in Recordings › Series to give it its own Start early and Keep recording after times. Default keeps using your Recordings setting, and episodes already scheduled move to the new times
- **Recordings shows up as soon as a network folder is set up,** so your TVs' recordings there can be watched even with nothing recorded on the phone

### Live TV
- **Live channels reconnect by themselves** when the provider drops them (with Auto-reconnect on), keeping your audio and subtitle choices, instead of stopping (thanks to bArKEr)
- Live channels connect to your provider once instead of three times, in full screen and Multiview. This may stop streams cutting out when Multiview or another device starts (thanks to bArKEr)
- Player error messages (can't open a channel, provider refused, too many connections, connection lost and the rest) now show in your phone's language

### Sync and backups
- The phone now keeps your TVs' **Usenet** providers and indexers (stored protected) when it syncs or backs up, so they aren't lost on the way to another TV (thanks to bArKEr)

### Faster and lighter
- A guide you've added twice in different forms (like a playlist's Xtream guide link and the one in its playlist file) is downloaded and read only once

## Fixed
- The app no longer closes for some people with very big playlists when a guide update lands while the guide is being read
- A playlist whose groups are all hidden (or hidden on this profile) no longer disappears from the playlist menu: it shows dimmed with the reason, and you can tap it to manage
- With only one profile, a playlist hidden on that profile can be shown again, and the playlist now says it's hidden
- Stalker portals that refuse the first login address now try the others instead of giving up (thanks to bArKEr)
- Sources that failed while the VPN or network was still connecting are searched again once it's ready, so a missing add-on shows up by itself (thanks to bArKEr)
- Posters load on VPNs that Android doesn't mark as having internet (thanks to bArKEr)
- Automatic sync keeps working when the copy it compares against on the phone can't be read (thanks to bArKEr)

## Special thanks
- [@ghbarker](https://github.com/ghbarker) (bArKEr) for live channel reconnecting, Usenet in sync and backups, and the VPN and Stalker fixes
