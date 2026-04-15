import { useState, useCallback } from 'react';
import { DiagnosisSession, SSEEvent } from '../types/api';
import { Play, Loader2, CheckCircle, XCircle, AlertCircle } from 'lucide-react';

export default function DiagnosisPage() {
  const [query, setQuery] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [events, setEvents] = useState<SSEEvent[]>([]);
  const [result, setResult] = useState<Partial<DiagnosisSession> | null>(null);

  const handleDiagnosis = useCallback(async () => {
    if (!query.trim()) return;

    setIsStreaming(true);
    setEvents([]);
    setResult(null);

    const token = localStorage.getItem('agentops_token');
    
    try {
      const response = await fetch('/api/v1/diagnosis/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
        },
        body: JSON.stringify({ query }),
      });

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            const data = line.slice(6);
            if (data) {
              try {
                const parsed = JSON.parse(data);
                const eventType = (response as any).event || 'message';
                
                setEvents(prev => [...prev, { event: eventType, data: parsed }]);

                if (eventType === 'result' || parsed.rootCause) {
                  setResult(parsed);
                }
              } catch (e) {
                // Skip invalid JSON
              }
            }
          }
        }
      }
    } catch (error) {
      console.error('Diagnosis error:', error);
    } finally {
      setIsStreaming(false);
    }
  }, [query]);

  const getEventIcon = (eventType: string) => {
    switch (eventType) {
      case 'result': return <CheckCircle className="w-4 h-4 text-green-500" />;
      case 'error': return <XCircle className="w-4 h-4 text-red-500" />;
      default: return <AlertCircle className="w-4 h-4 text-blue-500" />;
    }
  };

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold text-gray-900">Diagnosis</h1>
        <p className="text-gray-500 mt-1">Submit a natural language query to diagnose system issues</p>
      </div>

      <div className="bg-white rounded-xl shadow-sm p-6">
        <div className="flex gap-4">
          <textarea
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Describe the issue: e.g., '数据库响应变慢，最近10分钟有大量超时'"
            className="flex-1 px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent resize-none"
            rows={3}
            disabled={isStreaming}
          />
          <button
            onClick={handleDiagnosis}
            disabled={isStreaming || !query.trim()}
            className="px-6 py-3 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 disabled:opacity-50 flex items-center gap-2"
          >
            {isStreaming ? (
              <>
                <Loader2 className="w-5 h-5 animate-spin" />
                Diagnosing...
              </>
            ) : (
              <>
                <Play className="w-5 h-5" />
                Start Diagnosis
              </>
            )}
          </button>
        </div>
      </div>

      {result && (
        <div className="bg-white rounded-xl shadow-sm p-6">
          <h2 className="text-lg font-semibold mb-4">Diagnosis Result</h2>
          <div className="space-y-4">
            {result.rootCause && (
              <div>
                <h3 className="text-sm font-medium text-gray-500">Root Cause</h3>
                <p className="text-lg font-medium text-red-600 mt-1">{result.rootCause}</p>
              </div>
            )}
            {result.confidence !== undefined && (
              <div>
                <h3 className="text-sm font-medium text-gray-500">Confidence</h3>
                <p className="text-2xl font-bold text-green-600 mt-1">
                  {Math.round(result.confidence * 100)}%
                </p>
              </div>
            )}
            {result.fixSuggestion && (
              <div>
                <h3 className="text-sm font-medium text-gray-500">Fix Suggestion</h3>
                <p className="text-gray-900 mt-1">{result.fixSuggestion}</p>
              </div>
            )}
          </div>
        </div>
      )}

      {events.length > 0 && (
        <div className="bg-white rounded-xl shadow-sm">
          <div className="px-6 py-4 border-b">
            <h2 className="text-lg font-semibold">Live Trace</h2>
          </div>
          <div className="divide-y max-h-96 overflow-y-auto">
            {events.map((event, idx) => (
              <div key={idx} className="px-6 py-3 flex items-start gap-3">
                {getEventIcon(event.event)}
                <div className="flex-1 min-w-0">
                  <p className="text-xs text-gray-500 uppercase">{event.event}</p>
                  <p className="text-sm text-gray-900 mt-1 break-words">
                    {JSON.stringify(event.data).slice(0, 200)}
                    {JSON.stringify(event.data).length > 200 && '...'}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
