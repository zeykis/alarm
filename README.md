# Wake Alarm

Website button → your server → phone plays a loud alarm sound.
No Firebase, no third-party push service. Just a direct WebSocket
between your server and your phone.

## How it works

```
[Website button] --HTTP POST /trigger--> [Node server] --WebSocket--> [Phone app]
```

- The phone app opens one WebSocket connection to your server and keeps it
  open via a foreground service (with a permanent "Wake Alarm" notification —
  Android requires this to be visible while the service runs).
- When the website's button is pressed, the server sends `{"type":"ALARM"}`
  down that socket, instantly.
- The phone plays the sound on the **ALARM** audio stream (same stream a real
  alarm clock uses), at max volume, and shows a full-screen "STOP" screen even
  over the lock screen.
- The 5-minute cooldown is enforced **on the server**, so it can't be bypassed
  by refreshing the website.

## 1. Run the backend

```bash
cd backend
npm install
npm start
```

It listens on port 3000 and serves both the API and the website (`/`).

For your girlfriend to actually reach it and for your phone to connect from
anywhere (not just your home wifi), you need to host this somewhere public.
Easiest free options: Render, Railway, Fly.io — all support "deploy a Node
app" for free/cheap. Whatever host you use, take note of the URL, e.g.
`https://wake-alarm.onrender.com`.

## 2. Configure the website

Edit `website/index.html` and change:

```js
const SERVER_URL = "http://localhost:3000";
```

to your deployed backend URL. If your backend is served by the same Node
process (default setup above), the website is already reachable at
`https://your-backend-url/` — nothing else to change.

## 3. Build and install the Android app

Requirements: Android Studio (free), your phone with USB debugging or just
the built APK.

1. Open `android-app/` in Android Studio, let Gradle sync.
2. Build → Build APK, or just hit Run with your phone plugged in.
3. On first launch:
   - Grant the notification permission when asked.
   - Tap **"Disable Battery Optimization"** — this is the single most
     important step. Without it, Android will eventually kill the background
     connection and the alarm won't fire.
   - Enter your server's WebSocket URL, e.g. `wss://wake-alarm.onrender.com/phone`
     (use `wss://` if your host is HTTPS, `ws://` if it's plain HTTP like
     localhost testing).
   - Tap **Start Alarm Service**. You'll see a persistent "Connected — waiting"
     notification — that's the open socket.

## 4. Test it

Open the website, wait for it to say "Ready", press the button. Your phone
should immediately go full volume with a full-screen stop button, even if
it's locked.

## Known limitations (being upfront about these)

- **The service must stay running.** If you force-stop the app or Android
  aggressively kills it despite the battery optimization exemption (some
  phone brands — Xiaomi, Huawei, Oppo — are notorious for this beyond stock
  Android), the socket drops and the alarm won't fire until you reopen the
  app. Check your phone's manufacturer-specific "auto-start"/"protected apps"
  settings if this happens.
- **Needs internet on both ends.** No internet on the phone (wifi off, no
  data) means no alarm. This is inherent to any remote-trigger design.
- **Single device.** This is built for exactly one phone. Extending to
  multiple devices just means the server keeps a list of sockets instead of
  one — ask if you want that.
- If you'd rather not run/host a server yourself long-term, the tradeoff for
  that convenience is depending on a push service like Firebase — you said
  you didn't want that, so this version trades a little bit of "leave it
  running forever" reliability for zero third-party dependency.
