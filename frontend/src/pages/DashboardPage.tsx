import { useQuery } from '@tanstack/react-query';
import { diagnosisApi, healthApi } from '../lib/api';
import { Activity, CheckCircle, Clock, TrendingUp } from 'lucide-react';

export default function DashboardPage() {
  const { data: health } = useQuery({
    queryKey: ['health'],
    queryFn: healthApi.getHealth,
    refetchInterval: 30000,
  });

  const { data: history } = useQuery({
    queryKey: ['history', 0],
    queryFn: () => diagnosisApi.getHistory(0, 10),
  });

  const sessions = history?.data?.content || [];
  const totalDiagnoses = sessions.length;
  const successCount = sessions.filter((s: any) => s.status === 'COMPLETED').length;
  const avgLatency = sessions.length > 0
    ? Math.round(sessions.reduce((acc: number, s: any) => acc + (s.totalLatencyMs || 0), 0) / sessions.length)
    : 0;

  const stats = [
    { label: 'Total Diagnoses', value: totalDiagnoses, icon: Activity, color: 'blue' },
    { label: 'Success Rate', value: totalDiagnoses > 0 ? `${Math.round((successCount / totalDiagnoses) * 100)}%` : 'N/A', icon: CheckCircle, color: 'green' },
    { label: 'Avg Latency', value: avgLatency > 0 ? `${avgLatency}ms` : 'N/A', icon: Clock, color: 'purple' },
    { label: 'Health', value: health?.data?.status || 'UNKNOWN', icon: TrendingUp, color: health?.data?.status === 'UP' ? 'green' : 'red' },
  ];

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold text-gray-900">Dashboard</h1>
        <p className="text-gray-500 mt-1">System overview and recent activity</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {stats.map((stat) => {
          const Icon = stat.icon;
          return (
            <div key={stat.label} className="bg-white rounded-xl shadow-sm p-6">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-gray-500">{stat.label}</p>
                  <p className="text-2xl font-bold mt-1">{stat.value}</p>
                </div>
                <div className={`p-3 rounded-lg bg-${stat.color}-100`}>
                  <Icon className={`w-6 h-6 text-${stat.color}-600`} />
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <div className="bg-white rounded-xl shadow-sm">
        <div className="px-6 py-4 border-b">
          <h2 className="text-lg font-semibold">Recent Diagnoses</h2>
        </div>
        <div className="divide-y">
          {sessions.length === 0 ? (
            <div className="px-6 py-8 text-center text-gray-500">
              No diagnoses yet. Start a new diagnosis to see results here.
            </div>
          ) : (
            sessions.map((session: any) => (
              <div key={session.sessionId} className="px-6 py-4 hover:bg-gray-50">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="font-medium text-gray-900">{session.title || session.initialQuery}</p>
                    <p className="text-sm text-gray-500 mt-1">
                      {new Date(session.createdAt).toLocaleString()} • {session.intentType || 'GENERAL'}
                    </p>
                  </div>
                  <div className="flex items-center gap-4">
                    <span className={`px-3 py-1 rounded-full text-xs font-medium ${
                      session.status === 'COMPLETED'
                        ? 'bg-green-100 text-green-700'
                        : session.status === 'FAILED'
                        ? 'bg-red-100 text-red-700'
                        : 'bg-yellow-100 text-yellow-700'
                    }`}>
                      {session.status}
                    </span>
                    {session.confidence && (
                      <span className="text-sm text-gray-500">
                        {Math.round(session.confidence * 100)}% confidence
                      </span>
                    )}
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
