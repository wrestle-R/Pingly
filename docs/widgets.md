# Android widgets

Add the widgets from Pingora Settings or your launcher's widget picker. Tap either widget's body to open Pingora. When you pair more than one laptop, use the devices icon to choose which laptop each widget uses.

## Clipboard

Unlock the phone and tap **Send clipboard**. The button shows **Sending…** and changes to **Sent clipboard** once the laptop writes the clipboard. Older desktop builds can queue text without a delivery receipt. Android requires a momentarily focused surface to read the clipboard, so the widget uses a transparent activity and returns to the launcher after reading.

The Android sender accepts text up to 16 KiB, or PNG, JPEG, WebP, and GIF up to 4 MiB when the copying app exposes the image through Android's clipboard. Use matching Android and desktop builds for images. The image is transferred in memory through the paired connection. Paste it into a desktop app that accepts its format. Pingora keeps no clipboard history.

## Laptop status

The compact widget prioritizes the laptop name, battery and refresh button. Wider layouts show CPU and RAM. Taller layouts add internet status, the last-sync time, elapsed time, and the lid setting when available. Offline readings come from the most recent saved snapshot.

- Use refresh to request status without opening the app.
- Open **Power & lid controls** in Pingora to change the profile or choose what happens when you close the lid.

Power profiles require `powerprofilesctl` and a working power-profiles service. Internet status comes from NetworkManager. Slumber lid controls use the narrow IPC adapter documented under the release tools.

## Different networks

Connect the phone and laptop to the same Tailscale network. Keep Pingora running on the laptop, then add the laptop's Tailscale IPv4 address in **Settings → Private network addresses** on the phone. Pair in Pingora if the devices are new. The VPN shield identifies a connection between Tailscale-range addresses. See [Tailscale's device connection guide](https://tailscale.com/docs/how-to/connect-to-devices).

## Desktop background connection

The Linux installer creates a user-owned `pingora.service` when a user systemd manager is available. It starts at login and restarts after failure. Closing the desktop window hides it while sharing continues. Use Settings → Quit Pingora or `systemctl --user stop pingora.service` to disconnect intentionally. On other setups, keep Pingora running in the desktop session.
