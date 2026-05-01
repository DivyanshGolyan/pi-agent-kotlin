import { mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { streamSimple, fauxAssistantMessage, fauxText, fauxThinking, fauxToolCall, registerFauxProvider, Type } from "../../../reference/upstream/pi-mono/def47ec/packages/ai/src/index.ts";
import { Agent, agentLoop } from "../../../reference/upstream/pi-mono/def47ec/packages/agent/src/index.ts";
import { createAssistantMessageEventStream } from "../../../reference/upstream/pi-mono/def47ec/packages/ai/src/utils/event-stream.ts";
import { createAgentSession } from "../../../reference/upstream/pi-mono/def47ec/packages/coding-agent/src/core/sdk.ts";
import { createAgentSessionRuntime } from "../../../reference/upstream/pi-mono/def47ec/packages/coding-agent/src/core/agent-session-runtime.ts";
import { AuthStorage } from "../../../reference/upstream/pi-mono/def47ec/packages/coding-agent/src/core/auth-storage.ts";
import { ModelRegistry } from "../../../reference/upstream/pi-mono/def47ec/packages/coding-agent/src/core/model-registry.ts";
import { DefaultResourceLoader } from "../../../reference/upstream/pi-mono/def47ec/packages/coding-agent/src/core/resource-loader.ts";
import { SessionManager, buildSessionContext as buildCodingSessionContext } from "../../../reference/upstream/pi-mono/def47ec/packages/coding-agent/src/core/session-manager.ts";
import { SettingsManager } from "../../../reference/upstream/pi-mono/def47ec/packages/coding-agent/src/core/settings-manager.ts";

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
  if (message.role === "custom") {
    const normalized: any = {
      role: "custom",
      customType: message.customType,
      content: typeof message.content === "string" ? message.content : message.content.map(normalizeContentBlock),
      display: message.display
    };
    if (message.details !== undefined && message.details !== null) {
      normalized.details = message.details;
    }
    return normalized;
  }
  if (message.role === "branchSummary") {
    return {
      role: "branchSummary",
      summary: message.summary,
      fromId: message.fromId
    };
  }
  if (message.role === "compactionSummary") {
    return {
      role: "compactionSummary",
      summary: message.summary
    };
  }
  if (message.role === "bashExecution") {
    const normalized: any = {
      role: "bashExecution",
      command: message.command,
      output: message.output,
      cancelled: message.cancelled,
      truncated: message.truncated,
      excludeFromContext: message.excludeFromContext ?? false
    };
    if (message.exitCode !== undefined) {
      normalized.exitCode = message.exitCode;
    }
    if (message.fullOutputPath !== undefined) {
      normalized.fullOutputPath = message.fullOutputPath;
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

function normalizeAgentEventSequence(rawEvents: any[], suite = "pi-agent-core"): any[] {
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
          messages: event.messages.map((message: any) => normalizeMessage(message, suite))
        });
        break;
      case "turn_end":
        normalizedEvents.push({
          type: "turn_end",
          message: normalizeMessage(event.message, suite),
          toolResults: event.toolResults.map((message: any) => normalizeMessage(message, suite))
        });
        break;
      case "message_start":
        if (event.message.role === "assistant") {
          assistantPartial = baseAssistantMessage(event.message, suite);
          normalizedEvents.push({ type: "message_start", message: cloneJson(assistantPartial) });
        } else {
          normalizedEvents.push({ type: "message_start", message: normalizeMessage(event.message, suite) });
        }
        break;
      case "message_update": {
        const [nextAssistantPartial, assistantMessageEvent] = normalizeAssistantEvent(assistantPartial, event.assistantMessageEvent, suite);
        assistantPartial = cloneJson(nextAssistantPartial);
        normalizedEvents.push({
          type: "message_update",
          message: cloneJson(assistantPartial),
          assistantMessageEvent
        });
        break;
      }
      case "message_end":
        normalizedEvents.push({ type: "message_end", message: normalizeMessage(event.message, suite) });
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

function scenarioModels(scenario: any): any[] {
  const models = scenario.modelCatalog ?? (scenario.model ? [scenario.model] : []);
  if (!models.length) {
    throw new Error(`Scenario ${scenario.id} is missing modelCatalog/model`);
  }
  return models;
}

function createCodingTempDir(scenarioId: string): string {
  return path.join(repoRoot, "build", "parity-working", scenarioId);
}

function createCodingProviderRegistration(scenario: any) {
  const modelCatalog = scenarioModels(scenario);
  const registration = registerFauxProvider({
    api: modelCatalog[0].api,
    provider: modelCatalog[0].provider,
    tokenSize: scenario.provider?.tokenSize,
    models: modelCatalog.map((model: any) => ({
      id: model.id,
      name: model.name,
      reasoning: model.reasoning,
      input: model.input,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      contextWindow: model.contextWindow,
      maxTokens: model.maxTokens
    }))
  });

  const materializeModel = registration.getModel(scenario.initialModel ?? modelCatalog[0].id) ?? registration.models[0];
  registration.setResponses((scenario.responses ?? []).map((response: any) => materializeAssistantMessage(materializeModel, response)));
  return registration;
}

function buildProviderConfig(registration: any) {
  const model = registration.models[0];
  return {
    baseUrl: model.baseUrl,
    apiKey: "faux-key",
    api: registration.api,
    models: registration.models.map((candidate: any) => ({
      id: candidate.id,
      name: candidate.name,
      api: candidate.api,
      reasoning: candidate.reasoning,
      input: candidate.input,
      cost: candidate.cost,
      contextWindow: candidate.contextWindow,
      maxTokens: candidate.maxTokens,
      baseUrl: candidate.baseUrl
    }))
  };
}

async function createCodingServices(registration: any, cwd: string, agentDir: string, scenario: any) {
  const authStorage = AuthStorage.inMemory();
  authStorage.setRuntimeApiKey(registration.models[0].provider, "faux-key");
  const modelRegistry = ModelRegistry.inMemory(authStorage);
  modelRegistry.registerProvider(registration.models[0].provider, buildProviderConfig(registration));
  const settingsManager = SettingsManager.inMemory(scenario.settings ?? {});
  const resourceLoader = new DefaultResourceLoader({
    cwd,
    agentDir,
    noExtensions: true,
    noSkills: true,
    noPromptTemplates: true,
    noThemes: true,
    noContextFiles: true,
    systemPrompt: scenario.systemPrompt ?? "You are a parity test assistant."
  });
  await resourceLoader.reload();
  return {
    cwd,
    agentDir,
    authStorage,
    modelRegistry,
    settingsManager,
    resourceLoader,
    diagnostics: []
  };
}

function resolveScenarioModel(registration: any, scenario: any, modelId?: string) {
  const targetId = modelId ?? scenario.initialModel ?? scenarioModels(scenario)[0].id;
  const model = registration.getModel(targetId);
  if (!model) {
    throw new Error(`Unknown model ${targetId} for scenario ${scenario.id}`);
  }
  return model;
}

function resolveScopedModels(registration: any, scenario: any): any[] {
  return (scenario.scopedModels ?? []).map((scope: any) => ({
    model: resolveScenarioModel(registration, scenario, scope.model),
    thinkingLevel: scope.thinkingLevel
  }));
}

function createCodingNormalizer() {
  const entryIds = new Map<string, string>();
  const sessionIds = new Map<string, string>();
  const paths = new Map<string, string>();

  function normalizeEntryId(value: string | null | undefined): string | null | undefined {
    if (value === null || value === undefined) return value;
    if (value === "root") return value;
    if (!entryIds.has(value)) {
      entryIds.set(value, `entry-${entryIds.size + 1}`);
    }
    return entryIds.get(value)!;
  }

  function normalizeSessionId(value: string | null | undefined): string | null | undefined {
    if (value === null || value === undefined) return value;
    if (!sessionIds.has(value)) {
      sessionIds.set(value, `session-${sessionIds.size + 1}`);
    }
    return sessionIds.get(value)!;
  }

  function normalizePath(value: string | null | undefined): string | null | undefined {
    if (value === null || value === undefined) return value;
    if (!paths.has(value)) {
      paths.set(value, `path-${paths.size + 1}`);
    }
    return paths.get(value)!;
  }

  function normalizeCodingMessage(message: any): any {
    const normalized = normalizeMessage(message, "pi-coding-agent-core");
    if (normalized.role === "branchSummary") {
      normalized.fromId = normalizeEntryId(normalized.fromId);
    }
    if (normalized.role === "bashExecution" && normalized.fullOutputPath) {
      normalized.fullOutputPath = normalizePath(normalized.fullOutputPath);
    }
    return normalized;
  }

  function normalizeSessionEntry(entry: any): any {
    const normalized: any = {
      type: entry.type,
      id: normalizeEntryId(entry.id),
      parentId: normalizeEntryId(entry.parentId)
    };

    switch (entry.type) {
      case "message":
        normalized.message = normalizeCodingMessage(entry.message);
        break;
      case "thinking_level_change":
        normalized.thinkingLevel = entry.thinkingLevel;
        break;
      case "model_change":
        normalized.provider = entry.provider;
        normalized.modelId = entry.modelId;
        break;
      case "compaction":
        normalized.summary = entry.summary;
        normalized.firstKeptEntryId = normalizeEntryId(entry.firstKeptEntryId);
        if (entry.details === undefined || entry.details === null) {
          normalized.tokensBefore = entry.tokensBefore;
        }
        if (entry.details !== undefined) normalized.details = entry.details;
        normalized.fromHook = entry.fromHook ?? false;
        break;
      case "branch_summary":
        normalized.summary = entry.summary;
        normalized.fromId = normalizeEntryId(entry.fromId);
        if (entry.details !== undefined) normalized.details = entry.details;
        if (entry.fromHook !== undefined) normalized.fromHook = entry.fromHook;
        break;
      case "custom":
        normalized.customType = entry.customType;
        if (entry.data !== undefined) normalized.data = entry.data;
        break;
      case "custom_message":
        normalized.customType = entry.customType;
        normalized.content = entry.content;
        normalized.display = entry.display;
        if (entry.details !== undefined) normalized.details = entry.details;
        break;
      case "label":
        normalized.targetId = normalizeEntryId(entry.targetId);
        if (entry.label !== undefined) normalized.label = entry.label;
        break;
      case "session_info":
        if (entry.name !== undefined) normalized.name = entry.name;
        break;
      default:
        break;
    }

    return normalized;
  }

  function normalizeTreeNode(node: any): any {
    const normalized: any = {
      entry: normalizeSessionEntry(node.entry),
      children: node.children.map((child: any) => normalizeTreeNode(child))
    };
    if (node.label !== undefined) {
      normalized.label = node.label;
    }
    return normalized;
  }

  function normalizeSessionContext(context: any): any {
    return {
      messages: context.messages.map((message: any) => normalizeCodingMessage(message)),
      thinkingLevel: context.thinkingLevel,
      model: context.model
    };
  }

  function normalizeCompactionResult(result: any): any {
    if (!result) return null;
    const normalized: any = {
      summary: result.summary,
      firstKeptEntryId: normalizeEntryId(result.firstKeptEntryId)
    };
    if (result.details !== undefined && result.details !== null) {
      normalized.details = result.details;
    }
    return normalized;
  }

  function normalizeTreeNavigationResult(result: any): any {
    const normalized: any = {
      cancelled: result.cancelled
    };
    if (result.aborted) normalized.aborted = result.aborted;
    if (result.editorText !== undefined) normalized.editorText = result.editorText;
    if (result.summaryEntry) normalized.summaryEntry = normalizeSessionEntry(result.summaryEntry);
    return normalized;
  }

  function normalizeSessionSnapshot(session: any): any {
    return {
      sessionId: normalizeSessionId(session.sessionId),
      sessionFile: normalizePath(session.sessionFile),
      sessionName: session.sessionName ?? null,
      model: { provider: session.model.provider, modelId: session.model.id },
      thinkingLevel: session.thinkingLevel,
      autoCompactionEnabled: session.autoCompactionEnabled,
      isCompacting: session.isCompacting,
      steeringMode: session.steeringMode,
      followUpMode: session.followUpMode,
      steeringMessages: session.getSteeringMessages(),
      followUpMessages: session.getFollowUpMessages(),
      messages: session.messages.map((message: any) => normalizeCodingMessage(message)),
      entries: session.sessionManager.getEntries().map((entry: any) => normalizeSessionEntry(entry)),
      tree: session.sessionManager.getTree().map((node: any) => normalizeTreeNode(node)),
      leafId: normalizeEntryId(session.sessionManager.getLeafId()),
      stats: normalizeSessionStats(session.getSessionStats())
    };
  }

  function normalizeSessionStats(stats: any): any {
    return {
      sessionFile: normalizePath(stats.sessionFile),
      sessionId: normalizeSessionId(stats.sessionId),
      userMessages: stats.userMessages,
      assistantMessages: stats.assistantMessages,
      toolResults: stats.toolResults,
      totalMessages: stats.totalMessages,
      inputTokens: stats.inputTokens,
      outputTokens: stats.outputTokens,
      cacheReadTokens: stats.cacheReadTokens,
      cacheWriteTokens: stats.cacheWriteTokens,
      totalTokens: stats.totalTokens,
      totalCost: stats.totalCost,
      contextUsage:
        stats.contextUsage == null
          ? null
          : {
              tokens: null,
              contextWindow: stats.contextUsage.contextWindow
            }
    };
  }

  return {
    normalizeEntryId,
    normalizeSessionId,
    normalizePath,
    normalizeCodingMessage,
    normalizeSessionEntry,
    normalizeTreeNode,
    normalizeSessionContext,
    normalizeCompactionResult,
    normalizeTreeNavigationResult,
    normalizeSessionSnapshot,
    normalizeSessionStats
  };
}

function resolveSessionSelector(sessionManager: any, selector: any): string {
  if (typeof selector === "string") {
    return selector;
  }
  if (selector.entryId) {
    return selector.entryId;
  }
  if (selector.role) {
    const matches = sessionManager.getEntries().filter((entry: any) => entry.type === "message" && entry.message.role === selector.role);
    const target = matches[selector.index ?? 0];
    if (!target) {
      throw new Error(`Could not resolve selector ${JSON.stringify(selector)}`);
    }
    return target.id;
  }
  if (selector.type === "custom_message") {
    const matches = sessionManager.getEntries().filter((entry: any) => entry.type === "custom_message");
    const target = matches[selector.index ?? 0];
    if (!target) {
      throw new Error(`Could not resolve selector ${JSON.stringify(selector)}`);
    }
    return target.id;
  }
  throw new Error(`Unsupported selector ${JSON.stringify(selector)}`);
}

function normalizeCodingSessionEvents(rawEvents: any[], normalizer: ReturnType<typeof createCodingNormalizer>): any[] {
  const agentEvents = rawEvents.filter((event) =>
    [
      "agent_start",
      "turn_start",
      "agent_end",
      "turn_end",
      "message_start",
      "message_update",
      "message_end",
      "tool_execution_start",
      "tool_execution_update",
      "tool_execution_end"
    ].includes(event.type)
  );

  const normalizedAgentEvents = normalizeAgentEventSequence(agentEvents, "pi-coding-agent-core");
  let agentIndex = 0;

  return rawEvents.map((event: any) => {
    if (
      [
        "agent_start",
        "turn_start",
        "agent_end",
        "turn_end",
        "message_start",
        "message_update",
        "message_end",
        "tool_execution_start",
        "tool_execution_update",
        "tool_execution_end"
      ].includes(event.type)
    ) {
      const normalized = normalizedAgentEvents[agentIndex];
      agentIndex += 1;
      return normalized;
    }
    if (event.type === "queue_update") {
      return {
        type: "queue_update",
        steering: [...event.steering],
        followUp: [...event.followUp]
      };
    }
    if (event.type === "compaction_start") {
      return {
        type: "compaction_start",
        reason: event.reason
      };
    }
    if (event.type === "compaction_end") {
      const normalized: any = {
        type: "compaction_end",
        reason: event.reason,
        aborted: event.aborted ?? false,
        willRetry: event.willRetry ?? false
      };
      if (event.errorMessage) normalized.errorMessage = event.errorMessage;
      if (event.result) normalized.result = normalizer.normalizeCompactionResult(event.result);
      return normalized;
    }
    if (event.type === "session_info_changed") {
      const normalized: any = {
        type: "session_info_changed"
      };
      if (event.name !== undefined) normalized.name = event.name;
      return normalized;
    }
    if (event.type === "thinking_level_changed") {
      return {
        type: "thinking_level_changed",
        level: event.level
      };
    }
    throw new Error(`Unsupported coding-agent session event type: ${event.type}`);
  });
}

async function runCodingSessionManagerScenario(scenario: any): Promise<any> {
  const manager = SessionManager.inMemory();
  const aliases = new Map<string, string>();

  function resolveAlias(value: string | null | undefined): string | null | undefined {
    if (value === null || value === undefined) return value;
    return aliases.get(value) ?? value;
  }

  for (const operation of scenario.operations ?? []) {
    switch (operation.op) {
      case "appendMessage": {
        const id =
          operation.role === "user"
            ? manager.appendMessage(createUserMessage(operation.text, toTimestamp(aliases.size + 1)))
            : manager.appendMessage(
                createAssistantMessage(
                  {
                    api: scenarioModels(scenario)[0].api,
                    provider: scenarioModels(scenario)[0].provider,
                    id: operation.model ?? scenarioModels(scenario)[0].id
                  },
                  {
                    content: [{ type: "text", text: operation.text }],
                    stopReason: operation.stopReason ?? "stop"
                  },
                  100 + aliases.size
                )
              );
        if (operation.as) aliases.set(operation.as, id);
        break;
      }
      case "appendThinkingLevel": {
        const id = manager.appendThinkingLevelChange(operation.thinkingLevel);
        if (operation.as) aliases.set(operation.as, id);
        break;
      }
      case "appendModelChange": {
        const id = manager.appendModelChange(operation.provider, operation.modelId);
        if (operation.as) aliases.set(operation.as, id);
        break;
      }
      case "appendCompaction": {
        const id = manager.appendCompaction(operation.summary, resolveAlias(operation.firstKept), operation.tokensBefore ?? 1000, operation.details);
        if (operation.as) aliases.set(operation.as, id);
        break;
      }
      case "appendCustomMessage": {
        const id = manager.appendCustomMessageEntry(operation.customType, operation.content, operation.display, operation.details);
        if (operation.as) aliases.set(operation.as, id);
        break;
      }
      case "appendLabel": {
        const id = manager.appendLabelChange(resolveAlias(operation.target), operation.label);
        if (operation.as) aliases.set(operation.as, id);
        break;
      }
      case "appendSessionInfo": {
        const id = manager.appendSessionInfo(operation.name);
        if (operation.as) aliases.set(operation.as, id);
        break;
      }
      case "branch":
        manager.branch(resolveAlias(operation.target)!);
        break;
      case "resetLeaf":
        manager.resetLeaf();
        break;
      case "branchWithSummary": {
        const id = manager.branchWithSummary(resolveAlias(operation.parent) ?? null, operation.summary, operation.details, operation.fromHook);
        if (operation.as) aliases.set(operation.as, id);
        break;
      }
      default:
        throw new Error(`Unsupported session-manager operation ${operation.op}`);
    }
  }

  const normalizer = createCodingNormalizer();
  const entries = manager.getEntries();
  const byId = new Map(entries.map((entry: any) => [entry.id, entry]));
  const contexts = Object.fromEntries(
    Object.entries(scenario.inspect?.contexts ?? {}).map(([name, alias]) => [
      name,
      normalizer.normalizeSessionContext(buildCodingSessionContext(entries, resolveAlias(alias as string) ?? null, byId))
    ])
  );
  const branches = Object.fromEntries(
    Object.entries(scenario.inspect?.branches ?? {}).map(([name, alias]) => [
      name,
      manager.getBranch(resolveAlias(alias as string)).map((entry: any) => normalizer.normalizeSessionEntry(entry))
    ])
  );

  return {
    scenarioId: scenario.id,
    suite: scenario.suite,
    entries: entries.map((entry: any) => normalizer.normalizeSessionEntry(entry)),
    tree: manager.getTree().map((node: any) => normalizer.normalizeTreeNode(node)),
    leafId: normalizer.normalizeEntryId(manager.getLeafId()),
    contexts,
    branches
  };
}

async function runCodingAgentSessionScenario(scenario: any): Promise<any> {
  const tempDir = createCodingTempDir(scenario.id);
  await rm(tempDir, { recursive: true, force: true });
  await mkdir(tempDir, { recursive: true });
  const cwd = path.join(tempDir, "cwd");
  const agentDir = path.join(tempDir, "agent");
  await mkdir(cwd, { recursive: true });
  await mkdir(agentDir, { recursive: true });

  const registration = createCodingProviderRegistration(scenario);
  try {
    const services = await createCodingServices(registration, cwd, agentDir, scenario);
    const sessionManager = scenario.persisted ? SessionManager.create(cwd, path.join(tempDir, "sessions")) : SessionManager.inMemory();
    const result = await createAgentSession({
      cwd,
      agentDir,
      authStorage: services.authStorage,
      modelRegistry: services.modelRegistry,
      settingsManager: services.settingsManager,
      resourceLoader: services.resourceLoader,
      sessionManager,
      model: resolveScenarioModel(registration, scenario),
      thinkingLevel: scenario.initialThinkingLevel,
      scopedModels: resolveScopedModels(registration, scenario)
    });
    const session = result.session;
    const rawEvents: any[] = [];
    session.subscribe((event: any) => {
      rawEvents.push(event);
    });

    const operationResults: any[] = [];
    for (const operation of scenario.operations ?? []) {
      switch (operation.op) {
        case "prompt":
          await session.prompt(operation.text, operation.options);
          operationResults.push({ op: operation.op });
          break;
        case "sendCustomMessage":
          await session.sendCustomMessage(
            {
              role: "custom",
              customType: operation.customType,
              content: operation.content,
              display: operation.display,
              details: operation.details,
              timestamp: Date.now()
            },
            operation.triggerTurn ?? false,
            operation.deliverAs
          );
          operationResults.push({ op: operation.op });
          break;
        case "setThinkingLevel":
          session.setThinkingLevel(operation.level);
          operationResults.push({ op: operation.op, level: session.thinkingLevel });
          break;
        case "cycleThinkingLevel":
          operationResults.push({ op: operation.op, level: session.cycleThinkingLevel() ?? null });
          break;
        case "cycleModel":
          operationResults.push({ op: operation.op, result: await session.cycleModel(operation.direction ?? "forward") });
          break;
        case "setSessionName":
          session.setSessionName(operation.name);
          operationResults.push({ op: operation.op });
          break;
        case "navigateTree": {
          const targetId = resolveSessionSelector(session.sessionManager, operation.selector);
          operationResults.push({
            op: operation.op,
            result: await session.navigateTree(targetId, {
              summarize: operation.summarize ?? false,
              customInstructions: operation.customInstructions,
              replaceInstructions: operation.replaceInstructions ?? false,
              label: operation.label
            })
          });
          break;
        }
        case "compact":
          operationResults.push({ op: operation.op, result: await session.compact(operation.customInstructions) });
          break;
        default:
          throw new Error(`Unsupported coding-agent session operation ${operation.op}`);
      }
    }

    const normalizer = createCodingNormalizer();
    return {
      scenarioId: scenario.id,
      suite: scenario.suite,
      events: normalizeCodingSessionEvents(rawEvents, normalizer),
      operationResults: operationResults.map((entry) => {
        if (!entry.result) return entry;
        if (entry.op === "cycleModel") {
          return entry.result == null
            ? { op: entry.op, result: null }
            : {
                op: entry.op,
                result: {
                  model: { provider: entry.result.model.provider, modelId: entry.result.model.id },
                  thinkingLevel: entry.result.thinkingLevel,
                  isScoped: entry.result.isScoped
                }
              };
        }
        if (entry.op === "compact") {
          return { op: entry.op, result: normalizer.normalizeCompactionResult(entry.result) };
        }
        if (entry.op === "navigateTree") {
          return { op: entry.op, result: normalizer.normalizeTreeNavigationResult(entry.result) };
        }
        return entry;
      }),
      finalState: normalizer.normalizeSessionSnapshot(session)
    };
  } finally {
    registration.unregister();
    await rm(tempDir, { recursive: true, force: true });
  }
}

async function runCodingAgentRuntimeScenario(scenario: any): Promise<any> {
  const tempDir = createCodingTempDir(scenario.id);
  await rm(tempDir, { recursive: true, force: true });
  await mkdir(tempDir, { recursive: true });
  const cwd = path.join(tempDir, "cwd");
  const agentDir = path.join(tempDir, "agent");
  const sessionDir = path.join(tempDir, "sessions");
  await mkdir(cwd, { recursive: true });
  await mkdir(agentDir, { recursive: true });
  await mkdir(sessionDir, { recursive: true });

  const registration = createCodingProviderRegistration(scenario);
  const capturedSessionFiles = new Map<string, string>();

  try {
    const createRuntime = async ({ cwd: runtimeCwd, agentDir: runtimeAgentDir, sessionManager, sessionStartEvent }: any) => {
      const services = await createCodingServices(registration, runtimeCwd, runtimeAgentDir, scenario);
      const result = await createAgentSession({
        cwd: runtimeCwd,
        agentDir: runtimeAgentDir,
        authStorage: services.authStorage,
        modelRegistry: services.modelRegistry,
        settingsManager: services.settingsManager,
        resourceLoader: services.resourceLoader,
        sessionManager,
        model: resolveScenarioModel(registration, scenario),
        thinkingLevel: scenario.initialThinkingLevel,
        scopedModels: resolveScopedModels(registration, scenario),
        sessionStartEvent
      });
      return {
        ...result,
        services,
        diagnostics: services.diagnostics
      };
    };

    const runtime = await createAgentSessionRuntime(createRuntime, {
      cwd,
      agentDir,
      sessionManager: SessionManager.create(cwd, sessionDir)
    });

    const operationResults: any[] = [];
    for (const operation of scenario.operations ?? []) {
      switch (operation.op) {
        case "prompt":
          await runtime.session.prompt(operation.text, operation.options);
          operationResults.push({ op: operation.op });
          break;
        case "captureSessionFile":
          capturedSessionFiles.set(operation.as, runtime.session.sessionFile);
          operationResults.push({ op: operation.op, sessionFile: runtime.session.sessionFile });
          break;
        case "newSession":
          operationResults.push({ op: operation.op, result: await runtime.newSession() });
          break;
        case "switchSession":
          operationResults.push({
            op: operation.op,
            result: await runtime.switchSession(capturedSessionFiles.get(operation.sessionFile)!)
          });
          break;
        case "fork": {
          const targetId = resolveSessionSelector(runtime.session.sessionManager, operation.selector);
          operationResults.push({ op: operation.op, result: await runtime.fork(targetId) });
          break;
        }
        case "importFromJsonl":
          operationResults.push({
            op: operation.op,
            result: await runtime.importFromJsonl(capturedSessionFiles.get(operation.sessionFile)!)
          });
          break;
        default:
          throw new Error(`Unsupported coding-agent runtime operation ${operation.op}`);
      }
    }

    const normalizer = createCodingNormalizer();
    return {
      scenarioId: scenario.id,
      suite: scenario.suite,
      operationResults: operationResults.map((entry) => {
        if (entry.op === "captureSessionFile") {
          return { op: entry.op, sessionFile: normalizer.normalizePath(entry.sessionFile) };
        }
        if (entry.op === "fork") {
          return {
            op: entry.op,
            result: {
              cancelled: entry.result.cancelled,
              selectedText: entry.result.selectedText ?? null
            }
          };
        }
        return entry.result ? { op: entry.op, result: entry.result } : entry;
      }),
      runtime: {
        cwd: normalizer.normalizePath(runtime.cwd),
        diagnostics: runtime.diagnostics,
        modelFallbackMessage: runtime.modelFallbackMessage ?? null,
        session: normalizer.normalizeSessionSnapshot(runtime.session)
      }
    };
  } finally {
    registration.unregister();
    await rm(tempDir, { recursive: true, force: true });
  }
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
  if (scenario.kind === "coding_session_manager_scripted") {
    return runCodingSessionManagerScenario(scenario);
  }
  if (scenario.kind === "coding_agent_session_scripted") {
    return runCodingAgentSessionScenario(scenario);
  }
  if (scenario.kind === "coding_agent_runtime_scripted") {
    return runCodingAgentRuntimeScenario(scenario);
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
