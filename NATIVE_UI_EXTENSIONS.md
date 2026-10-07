# Native UI Plugin Contract

DSH Pocket keeps native rendering inside the Android app. A DSH plugin can customize the native chat by exposing the optional RPC `dshPocketUi/getNativeChatTheme`. The app calls it when the native chat opens. If the RPC is unavailable or returns an invalid value, the built-in theme is used.

The RPC takes no arguments and returns either the theme object directly or `{ "theme": ... }`:

```json
{
  "schemaVersion": 1,
  "userBubble": "#DCE7F3",
  "assistantBubble": "#F1F3F6",
  "cornerRadiusDp": 18,
  "fontScale": 1.0
}
```

`userBubble` and `assistantBubble` must be six-digit hex colors. The app clamps `cornerRadiusDp` to 8–28 and `fontScale` to 0.9–1.25. The plugin returns presentation data only: it cannot execute code in the Android process or access app-private state. Existing DSH plugins remain unchanged unless they opt into this RPC. The original DSH WebView remains available in Settings.

## AI-generated composer suggestions

The app also installs a small Host tool, `show_chat_suggestions`, into the local DSH runtime. The model may call it with two to four concise follow-up prompts when they are useful. Its result stores `meta.kind = "dsh-pocket/native-chat-suggestions"` and a `suggestions` array on the durable `tool/result` event. The native chat reads that event and displays those prompts as composer chips; tapping a chip fills the draft and leaves sending to the user. The WebView does not render this metadata.
