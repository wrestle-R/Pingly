import { Button } from "@/components/ui/button";
import type { Metadata } from "next";
import Link from "next/link";
export const metadata: Metadata = { title: "Docs" };
const topics = [
  [
    "getting-started",
    "Start here",
    "Get the signed Android APK and Linux installer from Downloads. Open Pingly on both devices and pair them on the same network.",
  ],
  [
    "pairing",
    "Pairing & permissions",
    "Compare the pairing codes on both devices before accepting. Review sharing permissions for each device in Settings. Android asks separately for notification access.",
  ],
  [
    "notifications",
    "Notifications & inbox",
    "Readable previews should preserve the newest message per conversation, with the full supplied notification text in the inbox. Reply and mark-as-read depend on the actions the source app exposes. Dismissal is separate from reading. History is bounded to 24 hours or 500 entries; clearing saved history does not dismiss source notifications. Android may withhold sensitive content, including OTPs.",
  ],
  [
    "clipboard",
    "Clipboard",
    "Copy text or an image on Android, then tap Send clipboard in Pingly, the Clipboard widget or the Quick Settings tile. Paste on your unlocked laptop. Pingly keeps no clipboard history.",
  ],
  [
    "sharing",
    "Files, links & folders",
    "Every incoming transfer asks for approval. Received links need an explicit Open action. Phone folder browsing is limited to folders you select and is read-only. Battery status and ring-device controls are also planned; ringing will have Stop and a timeout.",
  ],
  [
    "calls",
    "Optional calls",
    "Bluetooth is optional and reserved for incoming SIM calls. Answer, decline and both audio directions need verification on actual setups before they can be offered as supported. LAN sharing does not imply LAN cellular-call audio.",
  ],
  [
    "privacy",
    "Privacy & controls",
    "The core app must work without root. Per-feature controls, Pause sharing and safe diagnostic exports are planned. Message text, OTPs and clipboard contents must stay out of diagnostics by default. Friends’ locked-device content is hidden by default, with an explicit opt-in.",
  ],
  [
    "scope",
    "Scope & Slumber",
    "The initial targets are Android and Linux (Hyprland, Plasma and GNOME), with compatibility still to verify. Phone touchpad, keyboard, slide control, media controls, remote commands and a full SMS client are outside the core scope. Phone control for the owner’s native Slumber (Arch/Hyprland with QuickShell) is a separate requested integration. Its local status and activation interface has been identified; phone controls are not implemented or included in a release.",
  ],
  [
    "troubleshooting",
    "Troubleshooting & updates",
    "Once builds exist, check connection and permission health before exporting diagnostics. Reconnecting should produce a quiet summary rather than replaying every popup. Signed update instructions and package ownership rules will accompany actual releases; there is no update command to run today.",
  ],
];
export default function Docs() {
  return (
    <div className="document section">
      <div className="page-head">
        <span className="eyebrow">THE FIELD NOTES</span>
        <h1>
          A little context
          <br />
          before connecting.
        </h1>
        <p>
          Public product notes. We’ll add tested setup steps when the apps are
          ready.
        </p>
      </div>
      <div className="docs-layout">
        <aside>
          <nav aria-label="Documentation topics">
            {topics.map(([id, title]) => (
              <a key={id} href={"#" + id}>
                {title}
              </a>
            ))}
          </nav>
        </aside>
        <div>
          {topics.map(([id, title, text]) => (
            <section className="doc-topic" id={id} key={id}>
              <h2>{title}</h2>
              <p>{text}</p>
            </section>
          ))}
          <Button asChild variant="outline" size="lg"><Link href="/support">
            Need a hand? ↗
          </Link></Button>
        </div>
      </div>
    </div>
  );
}
