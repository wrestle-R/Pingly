# Android widgets

Add the widgets from Pingora Settings or your launcher's widget picker. Tap either widget's body to open Pingora. The Devices widget lists paired devices together. Use the devices icon on the Clipboard widget to pick a send destination.

## Clipboard

Unlock the phone and tap **Send clipboard**. The button shows **Sending…** and changes to **Sent clipboard** once the laptop writes the clipboard. Older desktop builds can queue text without a delivery receipt. Android requires a momentarily focused surface to read the clipboard, so the widget uses a transparent activity and returns to the launcher after reading.

The Android sender accepts text up to 16 KiB, or PNG, JPEG, WebP, and GIF up to 4 MiB when the copying app exposes the image through Android's clipboard. Use matching Android and desktop builds for images. The image is transferred in memory through the paired connection. Paste it into a desktop app that accepts its format. Pingora keeps no clipboard history.

## Devices and laptop status

With one paired device, the widget shows the laptop readings. With two, the widget shows both device names and connection states together. Tap a row to open that device, or tap refresh to request readings from the connected devices. The compact single-device view prioritizes the laptop name, battery and refresh button. Wider layouts show CPU and RAM. Taller layouts add internet status, the last-sync time, elapsed time, and the lid setting when available. Offline readings come from the most recent saved snapshot.

- Use refresh to request status without opening the app.
- Open **Power & lid controls** in Pingora to change the profile or choose what happens when you close the lid.

Power profiles require `powerprofilesctl` and a working power-profiles service. Internet status comes from NetworkManager. Slumber lid controls use the narrow IPC adapter documented under the release tools.

## InstaBox Resume

The **InstaBox Resume** widget sends a wake request over Pingora’s paired connection to the desktop relay. Keep the relay running and reachable while the InstaBox is paused. Tap refresh to check the VM without waking it. When the relay confirms the VM is reachable, the widget shows **Running** and disables the wake button. After a wake request, the widget checks again for the VM to come online.

The desktop enables this feature only when its private app data directory contains an `instabox-resume.json` file with mode `0600`. On Linux the directory is normally `~/.local/share/io.github.wrestle-r.pingora`. Set `phone_id` to the exact stable ID of the authorized paired phone and `helper` to an absolute path to a local executable that resumes one preconfigured InstaBox. Restart Pingora Desktop after configuring it. The helper takes no arguments. It reports accepted requests as JSON with `type: "resume"`, `http_status: "200"` or `"202"`, and `has_error: false`. Keep the provider credential with the relay helper. The Android app receives the request result and never needs that credential.

Set the optional `status_helper` to an absolute path to a separate read-only executable. It takes no arguments and returns exit code `0` when it confirms the VM is running. An SSH check can use `BatchMode=yes`, `ControlPath=none`, and a short connect timeout. A failed check yields **Status unknown**. The relay checks once more before waking and skips the wake when the VM is already reachable.

## Different networks

Connect the phone and laptop to the same Tailscale network. Keep Pingora running on the laptop, then add the laptop's Tailscale IPv4 address in **Settings → Private network addresses** on the phone. Pair in Pingora if the devices are new. The VPN shield identifies a connection between Tailscale-range addresses. See [Tailscale's device connection guide](https://tailscale.com/docs/how-to/connect-to-devices).

## Desktop background connection

The Linux installer creates a user-owned `pingora.service` when a user systemd manager is available. It starts at login and restarts after failure. Closing the desktop window hides it while sharing continues. Use Settings → Quit Pingora or `systemctl --user stop pingora.service` to disconnect intentionally. On other setups, keep Pingora running in the desktop session.
