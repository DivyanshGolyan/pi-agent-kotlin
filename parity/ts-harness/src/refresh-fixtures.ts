import { readdir, mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { streamSimple, fauxAssistantMessage, fauxText, fauxThinking, fauxToolCall, registerFauxProvider, Type } from "../../../reference/upstream/pi-mono/e3f6912/packages/ai/src/index.ts";
import { Agent, agentLoop } from "../../../reference/upstream/pi-mono/e3f6912/packages/agent/src/index.ts";
import { createAssistantMessageEventStream } from "../../../reference/upstream/pi-mono/e3f6912/packages/ai/src/utils/event-stream.ts";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, "../../..");
const scenariosDir = path.join(repoRoot, "parity", "scenarios");
const fixturesDir = path.join(repoRoot, "parity", "fixtures");

function toTimestamp(index: number): number {
  return index + 1;
}

function createUsage() {
  return {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
    totalTokens: 0,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 }
  };
}

function estimateTokens(text: string): number {
  return Math.ceil(text.length / 4);
}

function contentToText(content: string | Array<{ type: string; text?: string; data?: string; mimeType?: string }>): string {
  if (typeof content === "string") {
    return content;
  }
  return content
    .map((block) => {
      if (block.type === "text") {
        return block.text ?? "";
      }
      return `[image:${block.mimeType}:${(block.data ?? "").length}]`;
    })
    .join("\n");
}

function assistantContentToText(content: Array<any>): string {
  return content
    .map((block) => {
      if (block.type === "text") {
        return block.text;
      }
      if (block.type === "thinking") {
        return block.thinking;
      }
      return `${block.name}:${JSON.stringify(block.arguments)}`;
    })
    .join("\n");
}

function messageToText(message: any): string {
  if (message.role === "user") {
    return contentToText(message.content);
  }
  if (message.role === "assistant") {
    return assistantContentToText(message.content);
  }
  return [message.toolName, ...message.content.map((block: any) => contentToText([block]))].join("\n");
}

function serializeContext(context: any): string {
  const parts: string[] = [];
  if (context.systemPrompt) {
    parts.push(`system:${context.systemPrompt}`);
  }
  for (const message of context.messages) {
    parts.push(`${message.role}:${messageToText(message)}`);
  }
  if (context.tools?.length) {
    parts.push(`tools:${JSON.stringify(context.tools)}`);
  }
  return parts.join("\n\n");
}

function cloneAndEstimateUsage(message: any, context: any): any {
  const promptText = serializeContext(context);
  const input = estimateTokens(promptText);
  const output = estimateTokens(assistantContentToText(message.content));
  return {
    ...structuredClone(message),
    usage: {
      ...createUsage(),
      input,
      output,
      totalTokens: input + output
    }
  };
}

function splitStringByTokenSize(text: string, tokenSize: { min: number; max: number }): string[] {
  const size = Math.max(1, tokenSize.min) * 4;
  const chunks: string[] = [];
  for (let index = 0; index < text.length; index += size) {
    chunks.push(text.slice(index, index + size));
  }
  return chunks.length > 0 ? chunks : [""];
}

function materializeAssistantMessage(model: any, response: any): any {
  const blocks = response.content.map((block: any) => {
    if (block.type === "text") {
      return fauxText(block.text);
    }
    if (block.type === "thinking") {
      return fauxThinking(block.thinking);
    }
    return fauxToolCall(block.name, block.arguments, { id: block.id });
  });

  return {
    ...fauxAssistantMessage(blocks, {
      stopReason: response.stopReason,
      errorMessage: response.errorMessage,
      responseId: response.responseId,
      timestamp: 1
    }),
    api: model.api,
    provider: model.provider,
    model: model.id,
    usage: createUsage()
  };
}

function createUserMessage(content: any, timestamp: number): any {
  return {
    role: "user",
    content,
    timestamp
  };
}

function createAssistantMessage(model: any, response: any, timestamp: number): any {
  return {
    role: "assistant",
    content: response.content,
    api: model.api,
    provider: model.provider,
    model: model.id,
    usage: createUsage(),
    stopReason: response.stopReason,
    errorMessage: response.errorMessage,
    responseId: response.responseId,
    timestamp
  };
}

function createScriptedStreamFn(model: any, responses: any[], tokenSize: { min: number; max: number }) {
  let callIndex = 0;

  return () => {
    const stream = createAssistantMessageEventStream();
    const message = createAssistantMessage(model, responses[callIndex], 100 + callIndex);
    callIndex += 1;

    queueMicrotask(() => {
      const partial = { ...message, content: [] as any[] };
      stream.push({ type: "start", partial: { ...partial } });

      for (let index = 0; index < message.content.length; index += 1) {
        const block = message.content[index];
        if (block.type === "thinking") {
          partial.content = [...partial.content, { type: "thinking", thinking: "" }];
          stream.push({ type: "thinking_start", contentIndex: index, partial: { ...partial } });
          for (const chunk of splitStringByTokenSize(block.thinking, tokenSize)) {
            partial.content[index].thinking += chunk;
            stream.push({ type: "thinking_delta", contentIndex: index, delta: chunk, partial: { ...partial } });
          }
          stream.push({ type: "thinking_end", contentIndex: index, content: block.thinking, partial: { ...partial } });
          continue;
        }

        if (block.type === "text") {
          partial.content = [...partial.content, { type: "text", text: "" }];
          stream.push({ type: "text_start", contentIndex: index, partial: { ...partial } });
          for (const chunk of splitStringByTokenSize(block.text, tokenSize)) {
            partial.content[index].text += chunk;
            stream.push({ type: "text_delta", contentIndex: index, delta: chunk, partial: { ...partial } });
          }
          stream.push({ type: "text_end", contentIndex: index, content: block.text, partial: { ...partial } });
          continue;
        }

        partial.content = [...partial.content, { type: "toolCall", id: block.id, name: block.name, arguments: {} }];
        stream.push({ type: "toolcall_start", contentIndex: index, partial: { ...partial } });
        for (const chunk of splitStringByTokenSize(JSON.stringify(block.arguments), tokenSize)) {
          stream.push({ type: "toolcall_delta", contentIndex: index, delta: chunk, partial: { ...partial } });
        }
        partial.content[index].arguments = block.arguments;
        stream.push({ type: "toolcall_end", contentIndex: index, toolCall: block, partial: { ...partial } });
      }

      if (message.stopReason === "error" || message.stopReason === "aborted") {
        stream.push({ type: "error", reason: message.stopReason, error: message });
        stream.end(message);
      } else {
        stream.push({ type: "done", reason: message.stopReason, message });
        stream.end(message);
      }
    });

    return stream;
  };
}

function normalizeContentBlock(block: any): any {
  if (block.type === "text") {
    return { type: "text", text: block.text };
  }
  if (block.type === "thinking") {
    return { type: "thinking", thinking: block.thinking };
  }
  if (block.type === "toolCall") {
    return { type: "toolCall", id: block.id, name: block.name, arguments: block.arguments };
  }
  if (block.type === "image") {
    return { type: "image", data: block.data, mimeType: block.mimeType };
  }
  return block;
}

function normalizeMessage(message: any, suite: string): any {
  if (message.role === "user") {
    return { role: "user", content: message.content };
  }
  if (message.role === "toolResult") {
    const normalized: any = {
      role: "toolResult",
      toolCallId: message.toolCallId,
      toolName: message.toolName,
      isError: message.isError,
      content: message.content.map(normalizeContentBlock)
    };
    if (message.details !== undefined && message.details !== null) {
      normalized.details = message.details;
    }
    return normalized;
  }

  const normalized: any = {
    role: "assistant",
    stopReason: message.stopReason,
    content: message.content.map(normalizeContentBlock)
  };

  if (message.errorMessage) {
    normalized.errorMessage = message.errorMessage;
  }
  if (suite === "pi-ai-core") {
    normalized.api = message.api;
    normalized.provider = message.provider;
    normalized.model = message.model;
    normalized.usage = {
      input: message.usage.input,
      output: message.usage.output,
      cacheRead: message.usage.cacheRead,
      cacheWrite: message.usage.cacheWrite,
      totalTokens: message.usage.totalTokens
    };
    if (message.responseId) {
      normalized.responseId = message.responseId;
    }
  }
  return normalized;
}

function cloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}

function baseAssistantMessage(message: any, suite: string): any {
  const normalized = normalizeMessage(message, suite);
  return {
    ...normalized,
    content: []
  };
}

function normalizeAssistantEvent(partial: any | null, event: any, suite: string): [any | null, any] {
  switch (event.type) {
    case "start": {
      const next = baseAssistantMessage(event.partial, suite);
      return [next, { type: "start", partial: cloneJson(next) }];
    }
    case "text_start": {
      const next = cloneJson(partial);
      next.content.push({ type: "text", text: "" });
      return [next, { type: "text_start", contentIndex: event.contentIndex, partial: cloneJson(next) }];
    }
    case "text_delta": {
      const next = cloneJson(partial);
      next.content[event.contentIndex].text += event.delta;
      return [next, { type: "text_delta", contentIndex: event.contentIndex, delta: event.delta, partial: cloneJson(next) }];
    }
    case "text_end":
      return [partial, { type: "text_end", contentIndex: event.contentIndex, content: event.content, partial: cloneJson(partial) }];
    case "thinking_start": {
      const next = cloneJson(partial);
      next.content.push({ type: "thinking", thinking: "" });
      return [next, { type: "thinking_start", contentIndex: event.contentIndex, partial: cloneJson(next) }];
    }
    case "thinking_delta": {
      const next = cloneJson(partial);
      next.content[event.contentIndex].thinking += event.delta;
      return [next, { type: "thinking_delta", contentIndex: event.contentIndex, delta: event.delta, partial: cloneJson(next) }];
    }
    case "thinking_end":
      return [partial, { type: "thinking_end", contentIndex: event.contentIndex, content: event.content, partial: cloneJson(partial) }];
    case "toolcall_start": {
      const next = cloneJson(partial);
      next.content.push({
        type: "toolCall",
        id: event.partial.content[event.contentIndex].id,
        name: event.partial.content[event.contentIndex].name,
        arguments: {}
      });
      return [next, { type: "toolcall_start", contentIndex: event.contentIndex, partial: cloneJson(next) }];
    }
    case "toolcall_delta":
      return [partial, { type: "toolcall_delta", contentIndex: event.contentIndex, delta: event.delta, partial: cloneJson(partial) }];
    case "toolcall_end": {
      const next = cloneJson(partial);
      next.content[event.contentIndex] = normalizeContentBlock(event.toolCall);
      return [
        next,
        {
          type: "toolcall_end",
          contentIndex: event.contentIndex,
          toolCall: normalizeContentBlock(event.toolCall),
          partial: cloneJson(next)
        }
      ];
    }
    case "done":
      return [partial, { type: "done", reason: event.reason, message: normalizeMessage(event.message, suite) }];
    case "error":
      return [partial, { type: "error", reason: event.reason, error: normalizeMessage(event.error, suite) }];
    default:
      throw new Error(`Unsupported assistant event type: ${event.type}`);
  }
}

function normalizeAssistantEventSequence(rawEvents: any[], suite: string): any[] {
  let partial: any | null = null;
  const normalizedEvents: any[] = [];
  for (const event of rawEvents) {
    const [nextPartial, normalizedEvent] = normalizeAssistantEvent(partial, event, suite);
    partial = nextPartial;
    normalizedEvents.push(normalizedEvent);
  }
  return normalizedEvents;
}

function normalizeAgentEventSequence(rawEvents: any[]): any[] {
  let assistantPartial: any | null = null;
  const normalizedEvents: any[] = [];

  for (const event of rawEvents) {
    switch (event.type) {
      case "agent_start":
      case "turn_start":
        normalizedEvents.push({ type: event.type });
        break;
      case "agent_end":
        normalizedEvents.push({
          type: "agent_end",
          messages: event.messages.map((message: any) => normalizeMessage(message, "pi-agent-core"))
        });
        break;
      case "turn_end":
        normalizedEvents.push({
          type: "turn_end",
          message: normalizeMessage(event.message, "pi-agent-core"),
          toolResults: event.toolResults.map((message: any) => normalizeMessage(message, "pi-agent-core"))
        });
        break;
      case "message_start":
        if (event.message.role === "assistant") {
          assistantPartial = baseAssistantMessage(event.message, "pi-agent-core");
          normalizedEvents.push({ type: "message_start", message: cloneJson(assistantPartial) });
        } else {
          normalizedEvents.push({ type: "message_start", message: normalizeMessage(event.message, "pi-agent-core") });
        }
        break;
      case "message_update": {
        const [nextAssistantPartial, assistantMessageEvent] = normalizeAssistantEvent(assistantPartial, event.assistantMessageEvent, "pi-agent-core");
        assistantPartial = cloneJson(nextAssistantPartial);
        normalizedEvents.push({
          type: "message_update",
          message: cloneJson(assistantPartial),
          assistantMessageEvent
        });
        break;
      }
      case "message_end":
        normalizedEvents.push({ type: "message_end", message: normalizeMessage(event.message, "pi-agent-core") });
        if (event.message.role === "assistant") {
          assistantPartial = null;
        }
        break;
      case "tool_execution_start":
        normalizedEvents.push({
          type: "tool_execution_start",
          toolCallId: event.toolCallId,
          toolName: event.toolName,
          args: event.args
        });
        break;
      case "tool_execution_update":
        normalizedEvents.push({
          type: "tool_execution_update",
          toolCallId: event.toolCallId,
          toolName: event.toolName,
          args: event.args,
          partialResult: {
            content: event.partialResult.content.map(normalizeContentBlock),
            details: event.partialResult.details
          }
        });
        break;
      case "tool_execution_end":
        normalizedEvents.push({
          type: "tool_execution_end",
          toolCallId: event.toolCallId,
          toolName: event.toolName,
          result: {
            content: event.result.content.map(normalizeContentBlock),
            details: event.result.details
          },
          isError: event.isError
        });
        break;
      default:
        throw new Error(`Unsupported agent event type: ${event.type}`);
    }
  }

  return normalizedEvents;
}

function createCalculateToolTs() {
  const calculateSchema = Type.Object({
    expression: Type.String({ description: "The mathematical expression to evaluate" })
  });

  return {
    label: "Calculator",
    name: "calculate",
    description: "Evaluate mathematical expressions",
    parameters: calculateSchema,
    execute: async (_toolCallId: string, args: { expression: string }) => {
      const expression = args.expression.trim();
      const match = expression.match(/^(\d+)\s*([+\-*/])\s*(\d+)$/);
      if (!match) {
        throw new Error(`Unsupported expression: ${expression}`);
      }
      const left = Number(match[1]);
      const right = Number(match[3]);
      const result = match[2] === "*" ? left * right : match[2] === "+" ? left + right : match[2] === "-" ? left - right : left / right;
      return {
        content: [{ type: "text", text: `${expression} = ${result}` }],
        details: undefined
      };
    }
  };
}

async function runAiScenario(scenario: any): Promise<any> {
  const registration = registerFauxProvider({
    api: scenario.model.api,
    provider: scenario.model.provider,
    models: [
      {
        id: scenario.model.id,
        name: scenario.model.name,
        reasoning: scenario.model.reasoning,
        input: scenario.model.input,
        contextWindow: scenario.model.contextWindow,
        maxTokens: scenario.model.maxTokens
      }
    ],
    tokenSize: scenario.provider.tokenSize
  });

  try {
    registration.setResponses(
      scenario.responses.map((response: any) =>
        materializeAssistantMessage(
          {
            api: scenario.model.api,
            provider: scenario.model.provider,
            id: scenario.model.id
          },
          response
        )
      )
    );

    const context = {
      systemPrompt: scenario.context.systemPrompt,
      messages: scenario.context.messages.map((message: any, index: number) => createUserMessage(message.content, toTimestamp(index)))
    };

    const stream = streamSimple(registration.getModel(), context);
    const rawEvents: any[] = [];
    for await (const event of stream) {
      rawEvents.push(event);
    }
    const result = await stream.result();

    return {
      scenarioId: scenario.id,
      suite: scenario.suite,
      events: normalizeAssistantEventSequence(rawEvents, "pi-ai-core"),
      result: normalizeMessage(result, "pi-ai-core")
    };
  } finally {
    registration.unregister();
  }
}

async function runAgentLoopScenario(scenario: any): Promise<any> {
  const model = {
    id: scenario.model.id,
    name: scenario.model.name,
    api: scenario.model.api,
    provider: scenario.model.provider,
    baseUrl: "https://example.invalid",
    reasoning: scenario.model.reasoning,
    input: scenario.model.input,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: scenario.model.contextWindow,
    maxTokens: scenario.model.maxTokens
  };

  const streamFn = createScriptedStreamFn(model, scenario.responses, scenario.stream.tokenSize);
  const prompts = scenario.prompts.map((message: any, index: number) => createUserMessage(message.content, toTimestamp(index)));
  const context = {
    systemPrompt: scenario.context.systemPrompt,
    messages: [],
    tools: []
  };

  const stream = agentLoop(prompts, context, { model, convertToLlm: (messages) => messages }, undefined, streamFn);
  const rawEvents: any[] = [];
  for await (const event of stream) {
    rawEvents.push(event);
  }
  const messages = await stream.result();

  return {
    scenarioId: scenario.id,
    suite: scenario.suite,
    events: normalizeAgentEventSequence(rawEvents),
    messages: messages.map((message: any) => normalizeMessage(message, "pi-agent-core"))
  };
}

function materializeInitialMessages(model: any, messages: any[]): any[] {
  return messages.map((message: any, index: number) => {
    if (message.role === "user") {
      return createUserMessage(message.content, toTimestamp(index));
    }
    return createAssistantMessage(model, message, toTimestamp(index));
  });
}

async function runAgentScenario(scenario: any, isContinue: boolean): Promise<any> {
  const model = {
    id: scenario.model.id,
    name: scenario.model.name,
    api: scenario.model.api,
    provider: scenario.model.provider,
    baseUrl: "https://example.invalid",
    reasoning: scenario.model.reasoning,
    input: scenario.model.input,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: scenario.model.contextWindow,
    maxTokens: scenario.model.maxTokens
  };

  const streamFn = createScriptedStreamFn(model, scenario.responses, scenario.stream.tokenSize);
  const tools = (scenario.tools ?? []).map((toolId: string) => {
    if (toolId !== "calculate") {
      throw new Error(`Unsupported tool id: ${toolId}`);
    }
    return createCalculateToolTs();
  });

  const agent = new Agent({
    initialState: {
      systemPrompt: scenario.initialState.systemPrompt ?? "",
      model,
      thinkingLevel: scenario.initialState.thinkingLevel,
      tools,
      messages: materializeInitialMessages(model, scenario.initialState.messages ?? [])
    },
    streamFn
  });

  const rawEvents: any[] = [];
  const pendingToolCallsTimeline: any[] = [];
  agent.subscribe((event) => {
    rawEvents.push(event);
    if (event.type === "tool_execution_start" || event.type === "tool_execution_end") {
      pendingToolCallsTimeline.push({
        event: event.type,
        ids: [...agent.state.pendingToolCalls]
      });
    }
  });

  for (const message of scenario.steeringMessages ?? []) {
    agent.steer(createUserMessage(message.content, 50));
  }

  if (isContinue) {
    await agent.continue();
  } else {
    await agent.prompt(scenario.prompt.text);
  }

    return {
      scenarioId: scenario.id,
      suite: scenario.suite,
      events: normalizeAgentEventSequence(rawEvents),
      pendingToolCallsTimeline,
      state: {
        messages: agent.state.messages.map((message: any) => normalizeMessage(message, "pi-agent-core")),
      isStreaming: agent.state.isStreaming,
      pendingToolCalls: [...agent.state.pendingToolCalls].sort(),
      errorMessage: agent.state.errorMessage ?? null
    }
  };
}

async function runScenario(scenario: any): Promise<any> {
  if (scenario.kind === "ai_stream_faux") {
    return runAiScenario(scenario);
  }
  if (scenario.kind === "agent_loop_scripted") {
    return runAgentLoopScenario(scenario);
  }
  if (scenario.kind === "agent_prompt_scripted") {
    return runAgentScenario(scenario, false);
  }
  if (scenario.kind === "agent_continue_scripted") {
    return runAgentScenario(scenario, true);
  }
  throw new Error(`Unsupported scenario kind: ${scenario.kind}`);
}

async function main() {
  await mkdir(fixturesDir, { recursive: true });
  const scenarioFiles = (await readdir(scenariosDir))
    .filter((entry) => entry.endsWith(".json"))
    .sort();

  for (const fileName of scenarioFiles) {
    const scenarioPath = path.join(scenariosDir, fileName);
    const scenario = JSON.parse(await readFile(scenarioPath, "utf8"));
    const fixture = await runScenario(scenario);
    const fixturePath = path.join(fixturesDir, `${scenario.id}.json`);
    await writeFile(fixturePath, `${JSON.stringify(fixture, null, 2)}\n`, "utf8");
  }
}

await main();
