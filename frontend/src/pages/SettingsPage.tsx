import { useAuthStore } from '../stores/authStore';

export default function SettingsPage() {
  const { token, expiresAt, logout } = useAuthStore();

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold text-gray-900">Settings</h1>
        <p className="text-gray-500 mt-1">Manage your AgentOps configuration</p>
      </div>

      <div className="bg-white rounded-xl shadow-sm divide-y">
        <div className="p-6">
          <h2 className="text-lg font-semibold">Authentication</h2>
          <div className="mt-4 space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-500">Token Status</label>
              <p className="mt-1">
                {token ? (
                  <span className="text-green-600 font-medium">Authenticated</span>
                ) : (
                  <span className="text-red-600 font-medium">Not authenticated</span>
                )}
              </p>
            </div>
            {expiresAt && (
              <div>
                <label className="block text-sm font-medium text-gray-500">Expires At</label>
                <p className="mt-1 text-gray-900">{new Date(expiresAt).toLocaleString()}</p>
              </div>
            )}
          </div>
        </div>

        <div className="p-6">
          <h2 className="text-lg font-semibold">API Configuration</h2>
          <div className="mt-4 space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-500">API Base URL</label>
              <p className="mt-1 text-gray-900">{import.meta.env.VITE_API_URL || '/api/v1'}</p>
            </div>
          </div>
        </div>

        <div className="p-6">
          <h2 className="text-lg font-semibold text-red-600">Danger Zone</h2>
          <div className="mt-4">
            <button
              onClick={logout}
              className="px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700"
            >
              Logout
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
