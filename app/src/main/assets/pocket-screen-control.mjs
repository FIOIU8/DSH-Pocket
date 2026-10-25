import { defineTool } from "@deepseek-ai/dsh-tools";

export const inject = ["tools"];

const ACTIONS = ["read", "tap", "swipe", "text", "key", "screenshot", "request_permission"];
const KEYS = ["back", "home", "recents"];
const PERMISSIONS = ["accessibility", "overlay", "all_files"];
const BRIDGE_TIMEOUT_MS = 60_000;
// Granting happens in system settings and takes minutes, not seconds.
const PERMISSION_ASK_TIMEOUT_MS = 240_000;

/**
 * DSH Pocket screen control. The tool is a thin pipe: every call is POSTed to the
 * app-side loopback bridge (URL and token injected as env vars by the app), which runs
 * the deterministic rules, the review model and — for flagged ops — the user dialog
 * before touching the screen. The tool result is the verdict; a refusal message is
 * final and must never be retried with the same arguments.
 *
 * `output.render` receives (args, value) — the model's arguments first, the execute()
 * return second. A single-parameter render would silently show the model its own
 * arguments instead of the result.
 */
export function apply(ctx) {
  const base = (process.env.DSH_POCKET_BRIDGE_URL || "").replace(/\/+$/, "");
  const token = process.env.DSH_POCKET_BRIDGE_TOKEN || "";

  // The attachments service hosts images for model-visible content blocks. It is
  // optional: without it (or when its decoder is unavailable) screenshots degrade to
  // the saved path instead of failing the call.
  ctx.inject(["attachments"], () => {});

  async function callBridge(payload) {
    if (!base || !token) {
      return {
        ok: false,
        code: "BRIDGE_UNAVAILABLE",
        message: "屏幕操作桥未配置（DSH_POCKET_BRIDGE_URL / DSH_POCKET_BRIDGE_TOKEN 缺失），无法执行。",
      };
    }
    const timeoutMs = payload.action === "request_permission" ? PERMISSION_ASK_TIMEOUT_MS : BRIDGE_TIMEOUT_MS;
    const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
    const timer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
    try {
      const response = await fetch(`${base}/screen`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(payload),
        ...(controller ? { signal: controller.signal } : {}),
      });
      let body = null;
      try {
        body = await response.json();
      } catch {
        body = null;
      }
      if (!body || typeof body !== "object" || !("ok" in body)) {
        if (response.status === 403) {
          return {
            ok: false,
            code: "BRIDGE_UNAVAILABLE",
            message: "屏幕操作桥拒绝了鉴权（403）：App 与引擎持有的令牌不一致，请重启引擎后重试。",
          };
        }
        return {
          ok: false,
          code: "BRIDGE_UNAVAILABLE",
          message: `屏幕操作桥返回了无法解析的响应（HTTP ${response.status}）。`,
        };
      }
      return body;
    } catch (error) {
      const detail = error && error.message ? error.message : String(error);
      return {
        ok: false,
        code: "BRIDGE_UNAVAILABLE",
        message: `无法连接屏幕操作桥（${detail}）。App 可能已停止运行，本次调用失败。`,
      };
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  /**
   * Screenshots come back with a base64 JPEG for the model. Admitting it through the
   * attachments store turns it into a model-visible image block; the raw base64 must
   * never reach the model as text, so any failure here degrades to the saved path.
   */
  async function attachScreenshotImage(body) {
    const image = body && body.ok && body.data && body.data.image;
    if (!image || typeof image.base64 !== "string" || image.base64.length === 0) return body;
    const attachments = ctx.get("attachments");
    if (!attachments) return screenshotDegraded(body, "attachments 服务不可用");
    try {
      const ref = await attachments.saveImage({
        data: Buffer.from(image.base64, "base64"),
        mediaType: image.mediaType || "image/jpeg",
        name: screenshotName(body.data.path, image.mediaType || "image/jpeg"),
      });
      return {
        ok: true,
        data: {
          path: body.data.path,
          image: {
            attachmentId: ref.attachmentId,
            mediaType: ref.mediaType,
            bytes: ref.bytes,
            width: ref.width,
            height: ref.height,
            name: ref.name,
          },
        },
      };
    } catch (error) {
      const detail = error && error.message ? error.message : String(error);
      return screenshotDegraded(body, `内嵌图片不可用（${detail.slice(0, 120)}）`);
    }
  }

  function screenshotDegraded(body, reason) {
    return {
      ok: true,
      data: {
        path: body.data.path,
        imageInline: false,
        note: `${reason}；完整 PNG 已保存到上面的路径。`,
      },
    };
  }

  function screenshotName(path, mediaType) {
    const stem = typeof path === "string" ? (path.split("/").pop() || "screen").replace(/\.[^.]*$/, "") : "screen";
    const extension = mediaType === "image/png" ? "png" : "jpg";
    return `${stem}.${extension}`;
  }

  ctx.tools.register(defineTool({
    name: "pocket_screen_control",
    description:
      "Operate the physical Android screen the user is currently looking at. Actions: " +
      "read (dump a compact accessibility tree with texts, ids, bounds and depth), " +
      "tap (x, y in physical pixels — take coordinates from a previous read), swipe " +
      "(x, y to x2, y2), text (type into the focused input; tap the field first if there " +
      "is no focus), key (back | home | recents), screenshot (captures the screen and " +
      "returns the image inline in the result — you can see it directly; a full-size PNG " +
      "is also saved into the workspace and its path is included; you never need to read " +
      "the saved file yourself), request_permission (permission: accessibility | overlay | " +
      "all_files — pops a native dialog that walks the USER to that permission's " +
      "authorization page; use it when an operation was refused because a prerequisite is " +
      "off, or when a task needs the shared workspace and all-files access is missing — " +
      "do NOT just tell the user to dig through settings. It waits until the user grants " +
      "or cancels, so give it one clear `reason`. ok:false means the user declined: never " +
      "immediately re-request; explain in natural language instead). EVERY call must " +
      "include `reason`: a short (≤30 chars) explanation of why the operation is needed. " +
      "Each operation is shown to the user with an on-screen border and passes a safety " +
      "review; sensitive ops additionally ask the user. If a call returns ok:false it is " +
      "a final decision — NEVER retry the same operation, instead explain the refusal to " +
      "the user in natural language. Never operate on credential, banking or password " +
      "surfaces (typing passwords, authorizing payments).",
    parameters: {
      action: {
        type: "string",
        required: true,
        description: "One of: read | tap | swipe | text | key | screenshot | request_permission.",
        enum: ACTIONS,
      },
      reason: {
        type: "string",
        required: true,
        description: "为什么执行这个操作，30 字以内的中文或英文说明。审查与审计都会使用它。",
      },
      permission: {
        type: "string",
        description: "request_permission 动作要请求的权限：accessibility | overlay | all_files。",
        enum: PERMISSIONS,
      },
      x: { type: "number", description: "tap / swipe 起点的 X 坐标（物理像素）" },
      y: { type: "number", description: "tap / swipe 起点的 Y 坐标（物理像素）" },
      x2: { type: "number", description: "swipe 终点的 X 坐标（物理像素）" },
      y2: { type: "number", description: "swipe 终点的 Y 坐标（物理像素）" },
      text: { type: "string", description: "text 动作要输入的内容（≤512 字符）" },
      key: { type: "string", description: "key 动作要按的系统键", enum: KEYS },
    },
    output: {
      schema: { type: "object", additionalProperties: true },
      render: (_args, value) => {
        const image = value && value.ok && value.data && value.data.image;
        if (image && image.attachmentId) {
          return [
            { type: "text", text: `Screenshot captured; full-size PNG saved to ${value.data.path}.` },
            { type: "image", attachment: image },
          ];
        }
        return [{ type: "text", text: JSON.stringify(value) }];
      },
    },
    async execute(args) {
      const payload = { ...args };
      if (typeof payload.reason === "string" && payload.reason.length > 200) {
        payload.reason = payload.reason.slice(0, 200);
      }
      const body = await callBridge(payload);
      if (payload.action === "screenshot") {
        return attachScreenshotImage(body);
      }
      return body;
    },
  }));
}
