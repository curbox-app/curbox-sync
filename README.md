<p align="center">
  <img src="docs/icon.svg" width="112" alt="Curbox Sync icon">
</p>

<h1 align="center">Curbox Sync</h1>

<p align="center">
  Keeps <a href="https://github.com/curbox-app/curbox-android">Curbox</a> on your phone and the
  <a href="https://github.com/curbox-app/curbox-extension">Curbox extension</a> in your browser on the same page.
  <br>
  Over your own WiFi. No account. No cloud.
</p>

---

## What is this?

Curbox helps you stay off apps and sites that eat your time. It runs on your phone. There is also a Curbox
extension for your browser.

The problem: they do not talk to each other. Start focus on your phone, and your laptop still lets you open
YouTube.

**Curbox Sync fixes that.** It is a small app that sits next to Curbox on your phone. It talks to your
browser, and to your other phones, over WiFi. When one of them changes, the others change too.

## What it keeps in sync

- **Focus.** Start focus on your phone and it starts in your browser too. Stop it in one place and it stops
  everywhere. The time left is the same on both.
- **Pomodoro.** Work and break rounds line up, so your break starts at the same time on every device.
- **Focus groups.** Make a group on your phone, like "Study", and it shows up in your browser. Edit it in one
  place and the other one gets the change.
- **Screen time.** The **Usage** tab shows your time on your phone, your other phones and your browser, all
  in one chart. Each device has its own color.

## Why it is built this way

Curbox has no internet permission at all. That is on purpose, so it can never send your data anywhere.

Curbox Sync is a separate app, so Curbox can stay that way. It only talks to devices **you** paired, over
**your** WiFi. Nothing goes to a server. Everything it sends is locked with a key that only your devices
know.

## What you need

- An Android phone (Android 8 or newer)
- [Curbox](https://github.com/curbox-app/curbox-android) installed on it
- The [Curbox extension](https://github.com/curbox-app/curbox-extension) in your browser, and/or another
  phone with Curbox
- Both devices on the same WiFi (or [Tailscale](#sync-when-you-are-away-from-home), see below)

## Set it up

Open Curbox Sync. It walks you through three steps, one at a time:

1. **Turn on sync.** Tap **Turn on**.
2. **Allow access in Curbox.** Tap **Allow**. Curbox asks you once if this app may start and stop focus and
   read your screen time. Say yes.
3. **Pair your browser.** The app shows a short code, like `3Q2 W1S`. In your browser, open the Curbox
   extension, go to **Reducers**, then **Phone sync**, and type the code.

That's it. Keep the app open while you type the code. The code only works while the app is on screen.

### Add another phone

Install Curbox and Curbox Sync on the second phone too. Then:

1. On phone A, tap **Pair another device** to see its code.
2. On phone B, tap **The other phone shows a code? Type it here** and type phone A's code.

Now both phones stay in step. Your browsers are shared too: a browser you paired with one phone also works
with the other one. If one phone is off or gone, the browser finds the other one by itself. You do not need
to type another code.

## Sync when you are away from home

WiFi only works when your devices are on the same network. If you want sync to work from anywhere, like your
phone on mobile data and your laptop at school, use [Tailscale](https://tailscale.com). It is free.

1. Install Tailscale on your phone and your computer, and sign in to the same account on both.
2. In Curbox Sync, open **More** and tap **Turn on** next to Tailscale.

Your browser will find your phone through Tailscale when WiFi does not work.

## Tips

- **Sync stops when the screen is off?** Your phone may be saving battery. In Curbox Sync, tap **Allow** on
  the "Keep sync running" tip, or open **More**, then **Battery**.
- **No notification?** That is normal on newer Curbox. Curbox keeps Curbox Sync running for you, so it does
  not need its own notification.
- **Want to unpair?** Open **More**. Tap **Forget** next to a phone to unpair it on both phones. **Unpair
  all** removes every browser and phone. They need a new code to pair again.

## Is it safe?

- No account, no cloud, no ads, no tracking.
- It only answers devices on your home network (or your Tailscale).
- Pairing uses a code you type yourself, and each device gets its own secret key from it. Someone on your
  WiFi who does not know the code cannot read or change anything.
- The code changes after each pairing, and after too many wrong tries.

## For developers

- Kotlin, Jetpack Compose. Build it with `./gradlew assembleDebug`.
- It talks to Curbox through Curbox's public app API. It needs Curbox API 7 for Pomodoro and focus groups;
  older Curbox still syncs focus.
- How devices talk to each other (pairing, encryption, every message) is written up in
  [PROTOCOL.md](PROTOCOL.md).
