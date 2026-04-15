export interface DiagnosisSession {
  sessionId: string;
  initialQuery: string;
  title: string;
  status: string;
  intentType: string;
  rootCause: string;
  confidence: number;
  fixSuggestion: string;
  agentCount: number;
  createdAt: string;
  completedAt: string;
  totalLatencyMs: number;
}

export interface AgentTrace {
  stepIndex: number;
  agentName: string;
  stepType: 'THOUGHT' | 'ACTION' | 'OBSERVATION' | 'REFLECTION' | 'DECISION';
  content: string;
  toolName?: string;
  success?: boolean;
  timestamp: string;
}

export interface TraceTimelineItem {
  stepIndex: number;
  agentName: string;
  stepType: string;
  content: string;
  toolName?: string;
  success?: boolean;
  latencyMs?: number;
  timestamp: string;
}

export interface DiagnosisResult {
  sessionId: string;
  status: string;
  intentType: string;
  totalLatencyMs: number;
  rootCause?: string;
  confidence?: number;
  fixSuggestion?: string;
  markdown?: string;
}

export interface SSEEvent {
  event: string;
  data: Record<string, unknown>;
}

export interface KnowledgeEntry {
  id: number;
  category: string;
  title: string;
  content: string;
  tags: string[];
  matchPatterns: string[];
  priority: number;
  createdAt: string;
  updatedAt: string;
}

export interface AuthResponse {
  token: string;
  expiresIn: number;
  tokenType: string;
}

export interface HealthStatus {
  status: string;
  components: {
    db: { status: string };
    redis: { status: string };
    llm: { status: string };
  };
}
