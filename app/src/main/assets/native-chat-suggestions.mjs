import { defineTool } from "@deepseek-ai/dsh-tools";

export const inject = ["tools"];

export function apply(ctx) {
  ctx.tools.register(defineTool({
    name: "show_chat_suggestions",
    description:
      "Show 2 to 4 concise, relevant follow-up prompts as buttons in the native chat composer. " +
      "Before finishing your turn, call this when your answer naturally suggests useful next steps. " +
      "Make the suggestions specific to this conversation and directly usable by the user. Do not " +
      "call it for simple, self-contained answers, and never use generic filler suggestions.",
    parameters: {
      suggestions: {
        type: "array",
        required: true,
        description: "Two to four short follow-up prompts the user may choose to send.",
        items: { type: "string", description: "A concise, directly usable user prompt." },
      },
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        required: ["suggestions"],
        properties: {
          suggestions: {
            type: "array",
            minItems: 2,
            maxItems: 4,
            items: { type: "string", minLength: 2, maxLength: 80 },
          },
        },
      },
      render: () => [{ type: "text", text: "Follow-up suggestions are shown in the native chat composer." }],
      presentationMeta: (_args, value) => ({
        kind: "dsh-pocket/native-chat-suggestions",
        suggestions: value.suggestions,
      }),
    },
    async execute(args) {
      const suggestions = [...new Set(args.suggestions
        .map((item) => item.trim())
        .filter((item) => item.length >= 2 && item.length <= 80))].slice(0, 4);
      if (suggestions.length < 2) throw new Error("Provide at least two concise follow-up suggestions.");
      return { suggestions };
    },
  }));
}
